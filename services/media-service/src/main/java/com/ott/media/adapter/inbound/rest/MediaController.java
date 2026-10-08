package com.ott.media.adapter.inbound.rest;

import com.ott.media.application.MediaJobs;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MediaController {
  private final MediaJobs jobs;

  public MediaController(MediaJobs jobs) {
    this.jobs = jobs;
  }

  public record Upload(
      @NotNull UUID contentId,
      @Min(1) long assetVersion,
      @Min(1) @Max(268435456) long bytes,
      @Pattern(regexp = "[a-f0-9]{64}") @NotNull String sha256,
      @Pattern(regexp = "VOD|SPORT") String encodingProfile) {}

  @PostMapping("/v1/admin/media/uploads")
  public MediaJobs.Intake upload(@Valid @RequestBody Upload in) {
    return jobs.prepare(
        in.contentId(),
        in.assetVersion(),
        in.bytes(),
        in.sha256(),
        in.encodingProfile() == null ? "VOD" : in.encodingProfile());
  }

  @PostMapping("/v1/admin/media/jobs/{id}/upload-completed")
  public MediaJobs.Job completed(@PathVariable UUID id) {
    return jobs.completed(id);
  }

  @GetMapping("/v1/admin/media/jobs/{id}")
  public MediaJobs.Job get(@PathVariable UUID id) {
    return jobs.get(id);
  }
}
