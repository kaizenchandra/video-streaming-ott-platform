package com.ott.member.adapter.outbound.kafka;

import com.ott.contracts.events.MemberDeviceRevokedV1;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;

public final class DeviceEventEncoder {
  public static final org.apache.avro.Schema WRITER_SCHEMA = writerSchema();

  private DeviceEventEncoder() {}

  private static org.apache.avro.Schema writerSchema() {
    try (var resource =
        DeviceEventEncoder.class.getResourceAsStream("/events/member.device-revoked.v1.avsc")) {
      if (resource == null) throw new IllegalStateException("Packaged writer contract missing");
      return new org.apache.avro.Schema.Parser().parse(resource);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read writer contract", e);
    }
  }

  public static byte[] encode(
      int schemaId, UUID eventId, UUID deviceId, long version, long occurredAt, UUID accountId) {
    var event =
        MemberDeviceRevokedV1.newBuilder()
            .setEventId(eventId.toString())
            .setType("member.device-revoked.v1")
            .setAggregateId(deviceId.toString())
            .setAggregateVersion(version)
            .setOccurredAt(java.time.Instant.ofEpochMilli(occurredAt))
            .setProducer("member")
            .setTraceId(null)
            .setAccountId(accountId.toString())
            .setDeviceId(deviceId.toString())
            .setReason("OWNER_REVOKED")
            .build();
    try (var output = new ByteArrayOutputStream()) {
      output.write(0);
      output.write(ByteBuffer.allocate(4).putInt(schemaId).array());
      var encoder = EncoderFactory.get().binaryEncoder(output, null);
      new SpecificDatumWriter<MemberDeviceRevokedV1>(WRITER_SCHEMA).write(event, encoder);
      encoder.flush();
      return output.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot encode device event", e);
    }
  }
}
