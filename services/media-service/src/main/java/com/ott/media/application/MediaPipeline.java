package com.ott.media.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class MediaPipeline {
  private final ObjectStorage storage;
  private final MediaTools tools;
  private final ObjectMapper json;
  private final JsonNode profiles;

  public MediaPipeline(
      ObjectStorage storage, MediaTools tools, ObjectMapper json, JsonNode profiles) {
    this.storage = storage;
    this.tools = tools;
    this.json = json;
    this.profiles = profiles;
    if (profiles.path("version").asInt() < 1
        || profiles.path("renditions").size() < 1
        || profiles.path("renditions").size() > 8)
      throw new IllegalArgumentException("Invalid encoding profiles");
  }

  public String process(MediaJobs.Job job, Runnable heartbeat) {
    Path scratch = null;
    try {
      if (!job.encodingConfigHash()
          .equals(com.ott.media.infrastructure.EncodingSettings.hash(json, profiles)))
        throw new MediaFailure(503, "ENCODING_CONFIG_UNAVAILABLE");
      scratch = Files.createTempDirectory("ott-media-");
      var staged = scratch.resolve("package.zip");
      String stageKey = "staging/" + job.id() + "/package.zip";
      if (storage.resume(stageKey, staged)) {
        var resumed = extract(staged, scratch.resolve("resumed"), job);
        storage.publish(job.originPrefix(), resumed);
        return hash(resumed.resolve("_complete.json"));
      }
      var source = scratch.resolve("source.mp4");
      storage.download(job.sourceKey(), source, job.expectedBytes());
      String sourceChecksum = hash(source);
      if (!sourceChecksum.equals(job.sourceChecksum()) || Files.size(source) != job.expectedBytes())
        throw new MediaFailure(422, "SOURCE_CHECKSUM_MISMATCH");
      String probe =
          tools.run(
              scratch,
              "ffprobe",
              List.of(
                  "-v",
                  "error",
                  "-f",
                  "mov",
                  "-protocol_whitelist",
                  "file",
                  "-show_streams",
                  "-show_format",
                  "-of",
                  "json",
                  "source.mp4"),
              Duration.ofSeconds(30),
              heartbeat);
      var info = json.readTree(probe);
      var streams = new ArrayList<JsonNode>();
      info.path("streams").forEach(streams::add);
      var video =
          streams.stream()
              .filter(
                  s ->
                      s.path("codec_type").asText().equals("video")
                          && s.path("disposition").path("attached_pic").asInt() == 0)
              .findFirst()
              .orElseThrow(() -> new MediaFailure(422, "INVALID_MEDIA"));
      int width = video.path("width").asInt(), height = video.path("height").asInt();
      double duration = Double.parseDouble(info.path("format").path("duration").asText());
      if (width < 16
          || width > 3840
          || height < 16
          || height > 2160
          || !Double.isFinite(duration)
          || duration <= 0
          || duration > profiles.path("maxDurationSeconds").asInt())
        throw new MediaFailure(422, "INVALID_MEDIA");
      String[] rate = video.path("avg_frame_rate").asText().split("/");
      double sourceRate =
          rate.length == 2
              ? Double.parseDouble(rate[0]) / Double.parseDouble(rate[1])
              : Double.parseDouble(rate[0]);
      if (!Double.isFinite(sourceRate) || sourceRate <= 0 || sourceRate > 240)
        throw new MediaFailure(422, "INVALID_MEDIA");
      int fps =
          (int)
              Math.max(
                  1,
                  Math.min(
                      Math.floor(sourceRate),
                      profiles
                          .path("profiles")
                          .path(job.encodingProfile())
                          .path("maxFrameRate")
                          .asInt()));
      var renditions = new ArrayList<JsonNode>();
      profiles
          .path("renditions")
          .forEach(
              r -> {
                int h = r.path("height").asInt(), bitrate = r.path("bitrate").asInt();
                if (h < 16 || h % 2 != 0 || h > 2160 || bitrate < 32000 || bitrate > 20000000)
                  throw new IllegalArgumentException("Invalid rendition profile");
                if (h <= height) renditions.add(r);
              });
      if (renditions.isEmpty()) throw new MediaFailure(422, "INVALID_MEDIA");
      renditions.sort(java.util.Comparator.comparingInt(r -> r.path("height").asInt()));
      boolean audio = streams.stream().anyMatch(s -> s.path("codec_type").asText().equals("audio"));
      var args =
          new ArrayList<String>(
              List.of(
                  "-nostdin",
                  "-hide_banner",
                  "-loglevel",
                  "error",
                  "-y",
                  "-f",
                  "mov",
                  "-protocol_whitelist",
                  "file",
                  "-i",
                  "source.mp4"));
      for (var rendition : renditions)
        args.addAll(List.of("-map", "0:" + video.path("index").asInt()));
      if (audio) args.addAll(List.of("-map", "0:a?"));
      args.addAll(
          List.of(
              "-c:v",
              "libx264",
              "-preset",
              "veryfast",
              "-pix_fmt",
              "yuv420p",
              "-r",
              Integer.toString(fps),
              "-g",
              Integer.toString(2 * fps),
              "-keyint_min",
              Integer.toString(2 * fps),
              "-sc_threshold",
              "0",
              "-threads",
              "2"));
      for (int n = 0; n < renditions.size(); n++) {
        var r = renditions.get(n);
        args.addAll(
            List.of(
                "-filter:v:" + n,
                "scale=-2:" + r.path("height").asInt(),
                "-b:v:" + n,
                Integer.toString(
                    (int)
                        (r.path("bitrate").asInt()
                            * profiles
                                .path("profiles")
                                .path(job.encodingProfile())
                                .path("bitrateMultiplier")
                                .asDouble(1)))));
      }
      if (audio) args.addAll(List.of("-c:a", "aac", "-b:a", "64000", "-ac", "2"));
      args.addAll(
          List.of(
              "-f",
              "dash",
              "-dash_segment_type",
              "mp4",
              "-seg_duration",
              "2",
              "-use_template",
              "1",
              "-use_timeline",
              "1",
              "-hls_playlist",
              "1",
              "-hls_master_name",
              "master.m3u8",
              "-adaptation_sets",
              audio ? "id=0,streams=v id=1,streams=a" : "id=0,streams=v",
              "manifest.mpd"));
      tools.run(scratch, "ffmpeg", args, Duration.ofHours(2), heartbeat);
      tools.run(
          scratch,
          "ffmpeg",
          List.of(
              "-nostdin",
              "-hide_banner",
              "-loglevel",
              "error",
              "-y",
              "-f",
              "mov",
              "-protocol_whitelist",
              "file",
              "-i",
              "source.mp4",
              "-frames:v",
              "1",
              "-vf",
              "scale=" + Math.min(320, width) + ":-2",
              "thumbnail.jpg"),
          Duration.ofSeconds(30),
          heartbeat);
      var subtitles =
          streams.stream().filter(s -> s.path("codec_type").asText().equals("subtitle")).toList();
      if (subtitles.size() > 8) throw new MediaFailure(422, "INVALID_MEDIA");
      String master = Files.readString(scratch.resolve("master.m3u8"));
      for (int n = 0; n < subtitles.size(); n++) {
        String codec = subtitles.get(n).path("codec_name").asText();
        if (!java.util.Set.of("mov_text", "subrip", "webvtt").contains(codec))
          throw new MediaFailure(422, "INVALID_MEDIA");
        tools.run(
            scratch,
            "ffmpeg",
            List.of(
                "-nostdin",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "mov",
                "-protocol_whitelist",
                "file",
                "-i",
                "source.mp4",
                "-map",
                "0:s:" + n,
                "-c:s",
                "webvtt",
                "subs-" + n + ".vtt"),
            Duration.ofSeconds(30),
            heartbeat);
        Files.writeString(
            scratch.resolve("subs-" + n + ".m3u8"),
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:"
                + (int) Math.ceil(duration)
                + "\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:"
                + duration
                + ",\nsubs-"
                + n
                + ".vtt\n#EXT-X-ENDLIST\n");
        String lang = subtitles.get(n).path("tags").path("language").asText("und");
        if (!lang.matches("[a-z]{2,3}")) lang = "und";
        master =
            master.replaceFirst(
                "#EXTM3U",
                "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\""
                    + lang
                    + "-"
                    + n
                    + "\",LANGUAGE=\""
                    + lang
                    + "\",AUTOSELECT=YES,URI=\"subs-"
                    + n
                    + ".m3u8\"");
      }
      if (!subtitles.isEmpty()) {
        master = master.replaceAll("(?m)^(#EXT-X-STREAM-INF:.*)$", "$1,SUBTITLES=\"subs\"");
        Files.writeString(scratch.resolve("master.m3u8"), master);
        addDashSubtitles(scratch.resolve("manifest.mpd"), subtitles);
      }
      var output = Files.createDirectory(scratch.resolve("output"));
      var files = new TreeMap<String, String>();
      try (var paths = Files.list(scratch)) {
        for (var p : paths.filter(Files::isRegularFile).toList()) {
          String name = p.getFileName().toString();
          if (name.equals("source.mp4") || name.startsWith("tool-")) continue;
          if (!name.matches("[A-Za-z0-9_.-]{1,180}"))
            throw new MediaFailure(422, "INVALID_ASSET_PATH");
          Files.copy(p, output.resolve(name));
          files.put(name, hash(p));
        }
      }
      quality(output, duration, heartbeat);
      var tracks =
          streams.stream()
              .filter(
                  s ->
                      java.util.Set.of("audio", "subtitle").contains(s.path("codec_type").asText()))
              .map(
                  s ->
                      Map.of(
                          "type",
                          s.path("codec_type").asText(),
                          "language",
                          s.path("tags").path("language").asText("und")))
              .toList();
      byte[] manifest =
          json.writeValueAsBytes(
              Map.of(
                  "protection",
                  "MOCK_CLEAR_ONLY",
                  "encodingConfigHash",
                  job.encodingConfigHash(),
                  "encodingProfile",
                  job.encodingProfile(),
                  "encodingProfileVersion",
                  profiles.path("version").asInt(),
                  "sourceChecksum",
                  sourceChecksum,
                  "durationSeconds",
                  duration,
                  "tracks",
                  tracks,
                  "files",
                  files));
      Files.write(output.resolve("_complete.json"), manifest);
      freeze(output, staged);
      storage.stage(stageKey, staged);
      Files.delete(staged);
      if (!storage.resume(stageKey, staged))
        throw new IllegalStateException("Durable package missing after stage");
      var canonical = extract(staged, scratch.resolve("canonical"), job);
      String checksum = hash(canonical.resolve("_complete.json"));
      storage.publish(job.originPrefix(), canonical);
      return checksum;
    } catch (MediaFailure e) {
      throw e;
    } catch (java.io.IOException
        | IllegalArgumentException
        | javax.xml.parsers.ParserConfigurationException
        | org.xml.sax.SAXException
        | javax.xml.transform.TransformerException e) {
      throw new MediaFailure(422, "INVALID_MEDIA");
    } finally {
      if (scratch != null)
        try (var files = Files.walk(scratch)) {
          for (var p : files.sorted(java.util.Comparator.reverseOrder()).toList())
            Files.deleteIfExists(p);
        } catch (java.io.IOException e) {
          throw new IllegalStateException("Scratch cleanup failed", e);
        }
    }
  }

  private void freeze(Path output, Path archive) throws java.io.IOException {
    long total = 0;
    try (var paths = Files.list(output);
        var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
      for (var path : paths.sorted().toList()) {
        total += Files.size(path);
        if (total > 1073741824L) throw new MediaFailure(422, "PACKAGE_TOO_LARGE");
        zip.putNextEntry(new java.util.zip.ZipEntry(path.getFileName().toString()));
        Files.copy(path, zip);
        zip.closeEntry();
      }
    }
  }

  private Path extract(Path archive, Path directory, MediaJobs.Job job) throws java.io.IOException {
    Files.createDirectory(directory);
    long total = 0;
    int entries = 0;
    try (var zip = new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (++entries > 100000 || entry.isDirectory() || !name.matches("[A-Za-z0-9_.-]{1,180}"))
          throw new MediaFailure(422, "INVALID_ASSET_PATH");
        Path target = directory.resolve(name);
        try (var out = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
          byte[] buffer = new byte[65536];
          int n;
          while ((n = zip.read(buffer)) != -1) {
            total += n;
            if (total > 1073741824L) throw new MediaFailure(422, "PACKAGE_TOO_LARGE");
            out.write(buffer, 0, n);
          }
        }
        zip.closeEntry();
      }
    }
    var manifest = json.readTree(directory.resolve("_complete.json").toFile());
    if (!job.encodingConfigHash().equals(manifest.path("encodingConfigHash").asText())
        || !job.sourceChecksum().equals(manifest.path("sourceChecksum").asText())
        || !job.encodingProfile().equals(manifest.path("encodingProfile").asText())
        || profiles.path("version").asInt() != manifest.path("encodingProfileVersion").asInt()
        || !"MOCK_CLEAR_ONLY".equals(manifest.path("protection").asText()))
      throw new MediaFailure(409, "IMMUTABLE_ASSET_CONFLICT");
    var names = manifest.path("files").fieldNames();
    int expected = 1;
    while (names.hasNext()) {
      String name = names.next();
      requireFile(directory, name);
      if (!hash(directory.resolve(name)).equals(manifest.path("files").path(name).asText()))
        throw new MediaFailure(422, "INVALID_MANIFEST");
      expected++;
    }
    if (entries != expected) throw new MediaFailure(422, "INVALID_MANIFEST");
    return directory;
  }

  private void quality(Path output, double duration, Runnable heartbeat)
      throws java.io.IOException,
          javax.xml.parsers.ParserConfigurationException,
          org.xml.sax.SAXException {
    var mpd = xmlFactory().newDocumentBuilder().parse(output.resolve("manifest.mpd").toFile());
    if (mpd.getElementsByTagName("Representation").getLength() == 0
        || mpd.getElementsByTagName("SegmentTemplate").getLength() == 0)
      throw new MediaFailure(422, "INVALID_MANIFEST");
    try (var paths = Files.list(output)) {
      for (var playlist : paths.filter(p -> p.toString().endsWith("m3u8")).toList()) {
        String text = Files.readString(playlist);
        if (!text.startsWith("#EXTM3U")) throw new MediaFailure(422, "INVALID_MANIFEST");
        for (var line : text.split("\n")) {
          String ref = line.strip();
          if (ref.isEmpty()) continue;
          if (ref.startsWith("#")) {
            var matcher = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(ref);
            while (matcher.find()) requireFile(output, matcher.group(1));
          } else requireFile(output, ref);
        }
      }
    }
    // Decode every rendition's playlist, verifying segment references and actual independent
    // decoding.
    try (var paths = Files.list(output)) {
      for (var playlist :
          paths.filter(p -> p.getFileName().toString().matches("media_[0-9]+\\.m3u8")).toList()) {
        String probe =
            tools.run(
                output,
                "ffprobe",
                List.of(
                    "-v",
                    "error",
                    "-protocol_whitelist",
                    "file,crypto,data",
                    "-allowed_extensions",
                    "ALL",
                    "-show_format",
                    "-show_streams",
                    "-of",
                    "json",
                    playlist.getFileName().toString()),
                Duration.ofSeconds(30),
                heartbeat);
        var info = json.readTree(probe);
        double decoded = Double.parseDouble(info.path("format").path("duration").asText());
        if (!Double.isFinite(decoded)
            || Math.abs(decoded - duration) > 1
            || info.path("streams").isEmpty()) throw new MediaFailure(422, "INVALID_MANIFEST");
        tools.run(
            output,
            "ffmpeg",
            List.of(
                "-nostdin",
                "-hide_banner",
                "-loglevel",
                "error",
                "-protocol_whitelist",
                "file,crypto,data",
                "-allowed_extensions",
                "ALL",
                "-i",
                playlist.getFileName().toString(),
                "-t",
                "4",
                "-f",
                "null",
                "-"),
            Duration.ofSeconds(30),
            heartbeat);
      }
    }
  }

  private void requireFile(Path directory, String ref) {
    if (!ref.matches("[A-Za-z0-9_.-]{1,180}") || !Files.isRegularFile(directory.resolve(ref)))
      throw new MediaFailure(422, "INVALID_MANIFEST");
  }

  private void addDashSubtitles(Path path, List<JsonNode> subtitles)
      throws javax.xml.parsers.ParserConfigurationException,
          java.io.IOException,
          org.xml.sax.SAXException,
          javax.xml.transform.TransformerException {
    var document = xmlFactory().newDocumentBuilder().parse(path.toFile());
    var period = document.getElementsByTagName("Period").item(0);
    for (int n = 0; n < subtitles.size(); n++) {
      String lang = subtitles.get(n).path("tags").path("language").asText("und");
      if (!lang.matches("[a-z]{2,3}")) lang = "und";
      var adaptation = document.createElement("AdaptationSet");
      adaptation.setAttribute("mimeType", "text/vtt");
      adaptation.setAttribute("lang", lang);
      var representation = document.createElement("Representation");
      representation.setAttribute("id", "subs_" + n);
      representation.setAttribute("bandwidth", "256");
      var url = document.createElement("BaseURL");
      url.setTextContent("subs-" + n + ".vtt");
      representation.appendChild(url);
      adaptation.appendChild(representation);
      period.appendChild(adaptation);
    }
    var factory = javax.xml.transform.TransformerFactory.newInstance();
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    factory
        .newTransformer()
        .transform(
            new javax.xml.transform.dom.DOMSource(document),
            new javax.xml.transform.stream.StreamResult(path.toFile()));
  }

  private javax.xml.parsers.DocumentBuilderFactory xmlFactory()
      throws javax.xml.parsers.ParserConfigurationException {
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory;
  }

  private String hash(Path path) throws java.io.IOException {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var stream = Files.newInputStream(path)) {
        var buffer = new byte[65536];
        int n;
        while ((n = stream.read(buffer)) != -1) digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
