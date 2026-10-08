package com.ott.playback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class PlaybackApplication {
  public static void main(String[] args) {
    SpringApplication.run(PlaybackApplication.class, args);
  }
}
