package com.ott.member.application;

import com.ott.member.domain.model.MemberModels.Account;
import com.ott.member.domain.model.MemberModels.Device;
import com.ott.member.domain.model.MemberModels.Identity;
import com.ott.member.domain.model.MemberModels.Profile;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {
  private final MemberRepository repository;
  private final Clock clock;
  private final MeterRegistry metrics;
  private final ParentalService parental;

  public MemberService(
      MemberRepository repository, Clock clock, MeterRegistry metrics, ParentalService parental) {
    this.repository = repository;
    this.clock = clock;
    this.metrics = metrics;
    this.parental = parental;
  }

  @Transactional
  public Account provision(Identity identity) {
    return repository.provision(identity);
  }

  @Transactional(readOnly = true)
  public Account account(Identity identity) {
    return repository.account(identity).orElseThrow(() -> missing());
  }

  @Transactional(readOnly = true)
  public List<Profile> profiles(Identity identity) {
    return repository.profiles(account(identity).id());
  }

  @Transactional
  public Profile createProfile(
      Identity identity, String name, boolean kids, int maturity, String locale) {
    return createProfile(identity, name, kids, maturity, locale, null);
  }

  @Transactional
  public Profile createProfile(
      Identity identity, String name, boolean kids, int maturity, String locale, String pin) {
    validateProfile(name, kids, maturity, locale);
    long guarded = -1;
    if (!kids && maturity > 12) {
      requireRecentGuardian(identity);
      guarded = parental.guardian(identity, pin);
    }
    var account = account(identity);
    repository.lockAccount(account.id());
    if (guarded >= 0) parental.requireRevision(account.id(), guarded);
    if (repository.profiles(account.id()).size() >= 5)
      throw new MemberFailure(409, "PROFILE_LIMIT_REACHED", "Profile limit reached");
    var profile = new Profile(UUID.randomUUID(), name.strip(), kids, maturity, locale, 1);
    repository.insertProfile(account.id(), profile);
    metrics.counter("ott.member.profile.created").increment();
    return profile;
  }

  @Transactional
  public Profile updateProfile(
      Identity identity,
      UUID id,
      String name,
      boolean kids,
      int maturity,
      String locale,
      long version) {
    return updateProfile(identity, id, name, kids, maturity, locale, version, null);
  }

  @Transactional
  public Profile updateProfile(
      Identity identity,
      UUID id,
      String name,
      boolean kids,
      int maturity,
      String locale,
      long version,
      String pin) {
    validateProfile(name, kids, maturity, locale);
    var account = account(identity);
    var old = repository.profile(account.id(), id).orElseThrow(() -> missing());
    long guarded = -1;
    if ((old.kids() && !kids) || maturity > old.maturity()) {
      requireRecentGuardian(identity);
      guarded = parental.guardian(identity, pin);
    }
    repository.lockAccount(account.id());
    if (guarded >= 0) parental.requireRevision(account.id(), guarded);
    var changed = new Profile(id, name.strip(), kids, maturity, locale, old.version() + 1);
    if (version != old.version() || !repository.updateProfile(account.id(), changed, version))
      throw new MemberFailure(409, "VERSION_CONFLICT", "Profile was changed");
    repository.bumpSecurityEpoch(account.id());
    return changed;
  }

  @Transactional(readOnly = true)
  public List<Device> devices(Identity identity) {
    return repository.devices(account(identity).id());
  }

  @Transactional
  public Device registerDevice(
      Identity identity, String installation, String name, List<String> codecs, List<String> drm) {
    validateDevice(installation, name, codecs, drm);
    var account = account(identity);
    repository.lockAccount(account.id());
    var existing = repository.installation(account.id(), installation);
    if (existing.isPresent()) {
      var device = existing.get();
      if (!"ACTIVE".equals(device.status()))
        throw new MemberFailure(403, "DEVICE_NOT_ALLOWED", "Device was revoked");
      if (!device.name().equals(name.strip())
          || !device.codecs().equals(codecs)
          || !device.drm().equals(drm)) {
        throw new MemberFailure(
            409,
            "IDEMPOTENCY_CONFLICT",
            "Installation is already registered with different capabilities");
      }
      return device;
    }
    if (repository.devices(account.id()).stream().filter(d -> d.status().equals("ACTIVE")).count()
        >= 10) {
      metrics.counter("ott.member.device.denied", "reason", "limit").increment();
      throw new MemberFailure(409, "DEVICE_LIMIT_REACHED", "Device limit reached");
    }
    var device =
        new Device(UUID.randomUUID(), installation, name.strip(), codecs, drm, "ACTIVE", 1);
    repository.insertDevice(account.id(), device);
    return device;
  }

  @Transactional
  public void revokeDevice(Identity identity, UUID id) {
    var account = account(identity);
    repository.lockAccount(account.id());
    var old = repository.device(account.id(), id).orElseThrow(() -> missing());
    if (old.status().equals("REVOKED")) return;
    var changed =
        new Device(
            id,
            old.installationId(),
            old.name(),
            old.codecs(),
            old.drm(),
            "REVOKED",
            old.version() + 1);
    if (!repository.revokeDevice(account.id(), changed))
      throw new MemberFailure(409, "VERSION_CONFLICT", "Device was changed");
    repository.emitRevoked(account.id(), changed);
    repository.bumpSecurityEpoch(account.id());
    metrics.counter("ott.member.device.revoked").increment();
  }

  public record Eligibility(boolean allowed, int maturity, boolean kids, long securityEpoch) {}

  @Transactional(readOnly = true)
  public Eligibility deviceEligibility(UUID accountId, UUID profileId, UUID deviceId) {
    return deviceEligibility(accountId, profileId, deviceId, null, false);
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Eligibility deviceEligibility(
      UUID accountId, UUID profileId, UUID deviceId, String proof, boolean issued) {
    var profile = repository.profile(accountId, profileId);
    boolean allowed =
        repository.active(accountId)
            && profile.isPresent()
            && (issued
                || parental.allowed(
                    accountId,
                    profileId,
                    deviceId,
                    profile.get().version(),
                    profile.get().kids(),
                    profile.get().maturity(),
                    proof))
            && repository
                .device(accountId, deviceId)
                .map(d -> d.status().equals("ACTIVE"))
                .orElse(false);
    return new Eligibility(
        allowed,
        profile.map(Profile::maturity).orElse(0),
        profile.map(Profile::kids).orElse(true),
        repository.securityEpoch(accountId));
  }

  private void requireRecentGuardian(Identity identity) {
    var at = identity.authenticatedAt();
    if (at == null
        || at.isAfter(clock.instant())
        || Duration.between(at, clock.instant()).compareTo(Duration.ofSeconds(120)) > 0) {
      throw new MemberFailure(
          403, "GUARDIAN_REAUTH_REQUIRED", "Recent account-owner authentication required");
    }
  }

  private static MemberFailure missing() {
    return new MemberFailure(404, "RESOURCE_NOT_FOUND", "Resource not found");
  }

  private static void validateProfile(String name, boolean kids, int maturity, String locale) {
    if (name == null
        || name.isBlank()
        || name.strip().length() > 80
        || maturity < 0
        || maturity > 18
        || (kids && maturity > 12)
        || locale == null
        || locale.length() > 35) {
      throw new MemberFailure(400, "INVALID_PROFILE", "Invalid profile fields");
    }
    try {
      new Locale.Builder().setLanguageTag(locale).build();
    } catch (java.util.IllformedLocaleException e) {
      throw new MemberFailure(400, "INVALID_LOCALE", "Invalid locale");
    }
    if (locale.isBlank() || "und".equals(locale))
      throw new MemberFailure(400, "INVALID_LOCALE", "Explicit locale required");
  }

  private static void validateDevice(
      String installation, String name, List<String> codecs, List<String> drm) {
    if (installation == null
        || !installation.matches("[A-Za-z0-9_-]{8,128}")
        || name == null
        || name.isBlank()
        || name.strip().length() > 80
        || codecs == null
        || codecs.isEmpty()
        || codecs.size() > 3
        || drm == null
        || drm.size() > 3
        || !List.of("H264", "HEVC", "AV1").containsAll(codecs)
        || !List.of("WIDEVINE", "FAIRPLAY", "PLAYREADY").containsAll(drm)
        || codecs.stream().distinct().count() != codecs.size()
        || drm.stream().distinct().count() != drm.size()) {
      throw new MemberFailure(400, "INVALID_DEVICE", "Invalid device fields or capabilities");
    }
  }
}
