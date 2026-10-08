package com.ott.experience.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class EventConfiguration {
  @Bean
  DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, byte[]> kafka) {
    org.springframework.util.backoff.BackOff backoff =
        () ->
            new org.springframework.util.backoff.BackOffExecution() {
              private int attempts;

              public long nextBackOff() {
                return attempts >= 5
                    ? STOP
                    : Math.min(2000, 100L << attempts++)
                        + java.util.concurrent.ThreadLocalRandom.current().nextLong(50);
              }
            };
    var recoverer =
        new DeadLetterPublishingRecoverer(
            kafka,
            (record, error) ->
                new org.apache.kafka.common.TopicPartition(
                    record.topic() + ".dlq", record.partition()));
    recoverer.setFailIfSendResultIsError(true);
    var handler = new DefaultErrorHandler(recoverer, backoff);
    handler.addNotRetryableExceptions(IllegalArgumentException.class);
    return handler;
  }
}
