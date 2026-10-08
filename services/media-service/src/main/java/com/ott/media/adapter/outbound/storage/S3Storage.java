package com.ott.media.adapter.outbound.storage;

import com.ott.media.application.MediaFailure;
import com.ott.media.application.ObjectStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
public class S3Storage implements ObjectStorage {
  private final S3Client s3;
  private final S3Presigner signer;
  private final String quarantine, origin;

  public S3Storage(
      S3Client s3,
      S3Presigner signer,
      @Value("${ott.storage.quarantine}") String quarantine,
      @Value("${ott.storage.origin}") String origin) {
    this.s3 = s3;
    this.signer = signer;
    this.quarantine = quarantine;
    this.origin = origin;
  }

  public Upload presign(String key, long bytes, String checksum) {
    String encoded = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(checksum));
    var request =
        PutObjectRequest.builder()
            .bucket(quarantine)
            .key(key)
            .contentType("video/mp4")
            .contentLength(bytes)
            .checksumSHA256(encoded)
            .ifNoneMatch("*")
            .build();
    var signed =
        signer.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(request)
                .build());
    return new Upload(signed.url().toString(), signed.signedHeaders());
  }

  public Head head(String key) {
    software.amazon.awssdk.services.s3.model.HeadObjectResponse h;
    try {
      h =
          s3.headObject(
              HeadObjectRequest.builder()
                  .bucket(quarantine)
                  .key(key)
                  .checksumMode(software.amazon.awssdk.services.s3.model.ChecksumMode.ENABLED)
                  .build());
    } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
      if (e.statusCode() == 404) throw new MediaFailure(409, "UPLOAD_NOT_READY");
      throw e;
    }
    String checksum = h.checksumSHA256();
    if (checksum == null) throw new MediaFailure(409, "UPLOAD_CHECKSUM_MISSING");
    return new Head(
        h.contentLength(), HexFormat.of().formatHex(Base64.getDecoder().decode(checksum)));
  }

  public void download(String key, Path path, long maxBytes) {
    try (var response =
            s3.getObject(GetObjectRequest.builder().bucket(quarantine).key(key).build());
        var output = Files.newOutputStream(path)) {
      byte[] buffer = new byte[65536];
      long total = 0;
      int length;
      while ((length = response.read(buffer)) != -1) {
        total += length;
        if (total > maxBytes) throw new MediaFailure(422, "SOURCE_TOO_LARGE");
        output.write(buffer, 0, length);
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Source download failed", e);
    }
  }

  public void stage(String key, Path packageFile) {
    try {
      s3.putObject(
          PutObjectRequest.builder()
              .bucket(quarantine)
              .key(key)
              .contentType("application/zip")
              .ifNoneMatch("*")
              .build(),
          RequestBody.fromFile(packageFile));
    } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
      if (e.statusCode() != 412) throw e;
    }
  }

  public boolean resume(String key, Path packageFile) {
    try {
      var head = s3.headObject(HeadObjectRequest.builder().bucket(quarantine).key(key).build());
      if (head.contentLength() > 1073741824L) throw new MediaFailure(422, "PACKAGE_TOO_LARGE");
      download(key, packageFile, 1073741824L);
      return true;
    } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
      if (e.statusCode() == 404) return false;
      throw e;
    }
  }

  public void publish(String prefix, Path directory) {
    try (var files = Files.list(directory)) {
      var outputs =
          files
              .filter(Files::isRegularFile)
              .sorted(
                  (a, b) -> {
                    if (a.getFileName().toString().equals("_complete.json")) return 1;
                    if (b.getFileName().toString().equals("_complete.json")) return -1;
                    return a.compareTo(b);
                  })
              .toList();
      for (var p : outputs) {
        var name = p.getFileName().toString();
        if (!name.matches("[A-Za-z0-9_.-]{1,180}"))
          throw new MediaFailure(422, "INVALID_ASSET_PATH");
        String checksum =
            java.util.HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(p)));
        var put =
            PutObjectRequest.builder()
                .bucket(origin)
                .key(prefix + name)
                .metadata(java.util.Map.of("sha256", checksum, "protection", "MOCK_CLEAR_ONLY"))
                .ifNoneMatch("*")
                .contentType(type(name))
                .build();
        try {
          s3.putObject(put, RequestBody.fromFile(p));
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
          if (e.statusCode() != 412) throw e;
          var existing =
              s3.headObject(HeadObjectRequest.builder().bucket(origin).key(prefix + name).build());
          if (!checksum.equals(existing.metadata().get("sha256")))
            throw new MediaFailure(409, "IMMUTABLE_ASSET_CONFLICT");
        }
      }
    } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("Asset publication failed", e);
    }
  }

  private static String type(String name) {
    if (name.endsWith("m3u8")) return "application/vnd.apple.mpegurl";
    if (name.endsWith("mpd")) return "application/dash+xml";
    if (name.endsWith("jpg")) return "image/jpeg";
    if (name.endsWith("json")) return "application/json";
    return "video/mp4";
  }
}
