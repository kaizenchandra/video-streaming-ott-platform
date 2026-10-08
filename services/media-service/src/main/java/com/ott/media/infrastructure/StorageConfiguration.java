package com.ott.media.infrastructure;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class StorageConfiguration {
  @Bean
  S3Client s3(
      @Value("${ott.storage.region}") String region,
      @Value("${ott.storage.endpoint:}") String endpoint) {
    var builder =
        S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(2))
                    .socketTimeout(Duration.ofSeconds(30)))
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(Duration.ofSeconds(40))
                        .apiCallAttemptTimeout(Duration.ofSeconds(30))
                        .retryPolicy(software.amazon.awssdk.core.retry.RetryPolicy.none()))
            .requestChecksumCalculation(
                software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED);
    if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
    return builder.build();
  }

  @Bean
  S3Presigner presigner(
      @Value("${ott.storage.region}") String region,
      @Value("${ott.storage.endpoint:}") String endpoint) {
    var builder =
        S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create());
    if (!endpoint.isBlank())
      builder
          .endpointOverride(URI.create(endpoint))
          .serviceConfiguration(
              software.amazon.awssdk.services.s3.S3Configuration.builder()
                  .pathStyleAccessEnabled(true)
                  .build());
    return builder.build();
  }
}
