package com.ott.media;

@org.springframework.boot.autoconfigure.SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class MediaApplication {
  public static void main(String[] args) {
    org.springframework.boot.SpringApplication.run(MediaApplication.class, args);
  }
}
