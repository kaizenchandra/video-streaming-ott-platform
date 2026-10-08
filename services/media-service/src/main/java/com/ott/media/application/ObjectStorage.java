package com.ott.media.application;

import java.nio.file.Path;
import java.util.Map;

public interface ObjectStorage {
  record Upload(String url, Map<String, java.util.List<String>> headers) {}

  record Head(long bytes, String checksum) {}

  Upload presign(String key, long bytes, String checksum);

  Head head(String key);

  void download(String key, Path path, long maxBytes);

  void stage(String key, Path packageFile);

  boolean resume(String key, Path packageFile);

  void publish(String prefix, Path directory);
}
