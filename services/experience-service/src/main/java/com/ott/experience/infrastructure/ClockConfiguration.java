package com.ott.experience.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
