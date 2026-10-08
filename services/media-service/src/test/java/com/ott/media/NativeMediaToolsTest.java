package com.ott.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.media.adapter.outbound.process.NativeMediaTools;
import com.ott.media.application.MediaFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeMediaToolsTest {
  @TempDir Path directory;

  @Test
  void run_nativeEnvironment_excludesInheritedApplicationCredentials() throws Exception {
    var binary = directory.resolve("probe-fixture.sh");
    Files.writeString(
        binary,
        "#!/bin/sh\n"
            + "printf '%s|%s|%s' \"$HOME\" \"${JAVA_HOME-unset}\""
            + " \"${AWS_SECRET_ACCESS_KEY-unset}\"\n");
    assertThat(binary.toFile().setExecutable(true)).isTrue();
    var tools = new NativeMediaTools(binary.toString(), binary.toString());
    String output = tools.run(directory, "ffprobe", List.of(), Duration.ofSeconds(2), () -> {});
    assertThat(output).isEqualTo(directory + "|unset|unset");
    assertThat(directory.resolve("tool-output.txt")).doesNotExist();
    assertThat(directory.resolve("tool-errors.txt")).doesNotExist();
  }

  @Test
  void run_timeout_terminatesNativeTaskWithoutSuccess() throws Exception {
    var binary = directory.resolve("slow-fixture.sh");
    Files.writeString(binary, "#!/bin/sh\nexec sleep 5\n");
    assertThat(binary.toFile().setExecutable(true)).isTrue();
    var tools = new NativeMediaTools(binary.toString(), binary.toString());
    assertThatThrownBy(
            () -> tools.run(directory, "ffprobe", List.of(), Duration.ofMillis(20), () -> {}))
        .isInstanceOf(MediaFailure.class)
        .extracting("code")
        .isEqualTo("INVALID_MEDIA");
  }
}
