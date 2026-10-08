package com.ott.media.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ott.media.application.MediaPipeline;
import com.ott.media.application.MediaTools;
import com.ott.media.application.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
@ConditionalOnProperty(name = "ott.media.worker", havingValue = "true")
public class PipelineConfiguration {
  @Bean
  MediaPipeline pipeline(
      ObjectStorage storage,
      MediaTools tools,
      ObjectMapper json,
      @Value("${ott.media.profiles}") Resource profiles,
      @Value("${ott.media.protection:unconfigured}") String protection,
      org.springframework.core.env.Environment environment)
      throws java.io.IOException {
    if (!"mock-clear".equals(protection) || environment.matchesProfiles("prod"))
      throw new IllegalStateException(
          "Commercial DRM packager required; clear mock excluded from prod");
    try (var input = profiles.getInputStream()) {
      return new MediaPipeline(storage, tools, json, json.readTree(input));
    }
  }
}
