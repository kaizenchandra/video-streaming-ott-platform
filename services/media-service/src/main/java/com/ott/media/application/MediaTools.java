package com.ott.media.application;

import java.nio.file.Path;
import java.time.Duration;

public interface MediaTools {
  String run(
      Path directory,
      String tool,
      java.util.List<String> args,
      Duration timeout,
      Runnable heartbeat);
}
