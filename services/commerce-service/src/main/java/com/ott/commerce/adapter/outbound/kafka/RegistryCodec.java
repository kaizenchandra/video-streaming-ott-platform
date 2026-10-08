package com.ott.commerce.adapter.outbound.kafka;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RegistryCodec {
  private final RestClient registry;
  private final Map<String, Integer> ids = new ConcurrentHashMap<>();

  public RegistryCodec(
      io.micrometer.observation.ObservationRegistry observations,
      @Value("${ott.schema-registry.url}") String url) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(1000);
    factory.setReadTimeout(2000);
    registry =
        RestClient.builder()
            .observationRegistry(observations)
            .baseUrl(url)
            .requestFactory(factory)
            .build();
  }

  public Schema writer(String topic) {
    try (var resource = getClass().getResourceAsStream("/events/" + topic + ".avsc")) {
      if (resource == null) throw new IllegalStateException("Writer schema missing");
      return new Schema.Parser().parse(resource);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load writer schema", e);
    }
  }

  public byte[] encode(String topic, GenericData.Record record) {
    int id =
        ids.computeIfAbsent(
            topic,
            t -> {
              var r =
                  registry
                      .post()
                      .uri("/subjects/" + t + "-value")
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(Map.of("schemaType", "AVRO", "schema", record.getSchema().toString()))
                      .retrieve()
                      .body(Map.class);
              if (r == null || !(r.get("id") instanceof Number n))
                throw new IllegalStateException("Schema unavailable");
              return n.intValue();
            });
    try (var out = new ByteArrayOutputStream()) {
      out.write(0);
      out.write(ByteBuffer.allocate(4).putInt(id).array());
      var encoder = EncoderFactory.get().binaryEncoder(out, null);
      new GenericDatumWriter<GenericData.Record>(record.getSchema()).write(record, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot encode event", e);
    }
  }
}
