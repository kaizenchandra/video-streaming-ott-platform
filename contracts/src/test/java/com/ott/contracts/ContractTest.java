package com.ott.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.ott.contracts.v1.EligibilityDecision;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

class ContractTest {
  @Test
  void openApi_allContracts_parseWithoutErrors() throws Exception {
    try (var paths = Files.list(Path.of("openapi"))) {
      for (Path path : paths.filter(p -> p.toString().endsWith(".yaml")).toList()) {
        var result = new OpenAPIV3Parser().readLocation(path.toString(), null, null);
        assertThat(result.getMessages()).as(path.toString()).isEmpty();
        assertThat(result.getOpenAPI().getPaths()).isNotEmpty();
      }
    }
  }

  @Test
  void events_allSchemas_haveVersionAndEventIdentity() throws Exception {
    try (var paths = Files.list(Path.of("events"))) {
      var schemas = paths.filter(p -> p.toString().endsWith(".avsc")).toList();
      assertThat(schemas).hasSizeGreaterThanOrEqualTo(21);
      for (Path path : schemas) {
        var schema = new Schema.Parser().parse(path.toFile());
        assertThat(schema.getField("eventId")).isNotNull();
        assertThat(schema.getField("aggregateVersion").schema().getType())
            .isEqualTo(Schema.Type.LONG);
      }
    }
  }

  @Test
  void protobuf_eligibility_roundTripsBinary() throws Exception {
    var decision =
        EligibilityDecision.newBuilder()
            .setAccountId("account")
            .setAllowed(false)
            .setDenialCode("SUBSCRIPTION_INACTIVE")
            .setPolicyVersion(1)
            .build();
    assertThat(EligibilityDecision.parseFrom(decision.toByteArray())).isEqualTo(decision);
  }
}
