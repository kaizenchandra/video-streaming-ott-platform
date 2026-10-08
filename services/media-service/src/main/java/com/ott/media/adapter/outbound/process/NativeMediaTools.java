package com.ott.media.adapter.outbound.process;

import com.ott.media.application.MediaFailure;
import com.ott.media.application.MediaTools;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ott.media.worker", havingValue = "true")
public class NativeMediaTools implements MediaTools {
  private final String ffmpeg, ffprobe;

  public NativeMediaTools(
      @Value("${ott.media.ffmpeg}") String ffmpeg, @Value("${ott.media.ffprobe}") String ffprobe) {
    this.ffmpeg = ffmpeg;
    this.ffprobe = ffprobe;
  }

  public String run(
      Path directory,
      String tool,
      java.util.List<String> args,
      Duration timeout,
      Runnable heartbeat) {
    if (!java.util.Set.of("ffmpeg", "ffprobe").contains(tool))
      throw new IllegalArgumentException("Unknown native tool");
    var command = new java.util.ArrayList<String>();
    command.add(tool.equals("ffmpeg") ? ffmpeg : ffprobe);
    command.addAll(args);
    Process process = null;
    try {
      var output = directory.resolve("tool-output.txt").toFile();
      var errors = directory.resolve("tool-errors.txt").toFile();
      var builder =
          new ProcessBuilder(command)
              .directory(directory.toFile())
              .redirectOutput(output)
              .redirectError(errors);
      var nativeEnvironment = builder.environment();
      nativeEnvironment.clear();
      nativeEnvironment.put("PATH", "/usr/local/bin:/usr/bin:/bin");
      nativeEnvironment.put("HOME", directory.toString());
      nativeEnvironment.put("TMPDIR", directory.toString());
      nativeEnvironment.put("LANG", "C.UTF-8");
      String libraries = System.getenv("LD_LIBRARY_PATH");
      if (libraries != null) nativeEnvironment.put("LD_LIBRARY_PATH", libraries);
      process = builder.start();
      long end = System.nanoTime() + timeout.toNanos();
      while (!process.waitFor(1, TimeUnit.SECONDS)) {
        heartbeat.run();
        if (System.nanoTime() > end) throw new MediaFailure(422, "INVALID_MEDIA");
        if (output.length() > 1048576 || errors.length() > 1048576)
          throw new MediaFailure(422, "INVALID_MEDIA");
      }
      if (process.exitValue() != 0 || output.length() > 1048576)
        throw new MediaFailure(422, "INVALID_MEDIA");
      return java.nio.file.Files.readString(output.toPath());
    } catch (java.io.IOException e) {
      throw new MediaFailure(503, "MEDIA_TOOLS_UNAVAILABLE");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Media task interrupted", e);
    } finally {
      if (process != null && process.isAlive()) {
        // Tools execute directly, without a shell or child-process wrapper.
        process.destroyForcibly();
        try {
          process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      try {
        java.nio.file.Files.deleteIfExists(directory.resolve("tool-output.txt"));
        java.nio.file.Files.deleteIfExists(directory.resolve("tool-errors.txt"));
      } catch (java.io.IOException e) {
        throw new IllegalStateException("Native diagnostic cleanup failed", e);
      }
    }
  }
}
