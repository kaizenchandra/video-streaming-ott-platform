package com.ott.insights;

@org.springframework.boot.autoconfigure.SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class InsightsApplication {
  public static void main(String[] args) {
    org.springframework.boot.SpringApplication.run(InsightsApplication.class, args);
  }
}
