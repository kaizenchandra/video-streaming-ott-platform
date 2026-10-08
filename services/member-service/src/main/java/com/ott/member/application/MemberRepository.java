package com.ott.member.application;

import com.ott.member.domain.model.MemberModels.Account;
import com.ott.member.domain.model.MemberModels.Device;
import com.ott.member.domain.model.MemberModels.Identity;
import com.ott.member.domain.model.MemberModels.Profile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemberRepository {
  Account provision(Identity identity);

  Optional<Account> account(Identity identity);

  void lockAccount(UUID accountId);

  boolean active(UUID accountId);

  long securityEpoch(UUID accountId);

  void bumpSecurityEpoch(UUID accountId);

  List<Profile> profiles(UUID accountId);

  Optional<Profile> profile(UUID accountId, UUID id);

  void insertProfile(UUID accountId, Profile profile);

  boolean updateProfile(UUID accountId, Profile profile, long expectedVersion);

  List<Device> devices(UUID accountId);

  Optional<Device> installation(UUID accountId, String installationId);

  Optional<Device> device(UUID accountId, UUID id);

  void insertDevice(UUID accountId, Device device);

  boolean revokeDevice(UUID accountId, Device device);

  void emitRevoked(UUID accountId, Device device);
}
