package com.ott.member.adapter.outbound.persistence;

import com.ott.member.application.MemberRepository;
import com.ott.member.domain.model.MemberModels.Account;
import com.ott.member.domain.model.MemberModels.Device;
import com.ott.member.domain.model.MemberModels.Identity;
import com.ott.member.domain.model.MemberModels.Profile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMemberRepository implements MemberRepository {
  private final JdbcTemplate jdbc;

  public JdbcMemberRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Account provision(Identity identity) {
    jdbc.update(
        "INSERT INTO account(id,issuer,subject) VALUES (?,?,?) ON CONFLICT (issuer,subject) DO"
            + " NOTHING",
        UUID.randomUUID(),
        identity.issuer(),
        identity.subject());
    return account(identity).orElseThrow();
  }

  public Optional<Account> account(Identity identity) {
    return jdbc
        .query(
            "SELECT id,created_at,status FROM account WHERE issuer=? AND subject=?",
            (rs, n) -> {
              if (!"ACTIVE".equals(rs.getString("status")))
                throw new com.ott.member.application.MemberFailure(
                    403, "ACCOUNT_RESTRICTED", "Account restricted");
              return new Account(
                  rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant());
            },
            identity.issuer(),
            identity.subject())
        .stream()
        .findFirst();
  }

  public void lockAccount(UUID id) {
    jdbc.queryForObject("SELECT id FROM account WHERE id=? FOR UPDATE", UUID.class, id);
  }

  public boolean active(UUID id) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM account WHERE id=? AND status='ACTIVE'", Integer.class, id)
        == 1;
  }

  public long securityEpoch(UUID id) {
    return jdbc
        .queryForList("SELECT security_epoch FROM account WHERE id=?", Long.class, id)
        .stream()
        .findFirst()
        .orElse(0L);
  }

  public void bumpSecurityEpoch(UUID id) {
    jdbc.update("UPDATE account SET security_epoch=security_epoch+1 WHERE id=?", id);
  }

  public List<Profile> profiles(UUID account) {
    return jdbc.query(
        "SELECT * FROM profile WHERE account_id=? ORDER BY created_at,id",
        (rs, n) -> profile(rs),
        account);
  }

  public Optional<Profile> profile(UUID account, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM profile WHERE account_id=? AND id=?",
            (rs, n) -> profile(rs),
            account,
            id)
        .stream()
        .findFirst();
  }

  public void insertProfile(UUID account, Profile p) {
    jdbc.update(
        "INSERT INTO profile(id,account_id,name,kids,maturity,locale,version) VALUES"
            + " (?,?,?,?,?,?,?)",
        p.id(),
        account,
        p.name(),
        p.kids(),
        p.maturity(),
        p.locale(),
        p.version());
  }

  public boolean updateProfile(UUID account, Profile p, long expected) {
    return jdbc.update(
            "UPDATE profile SET name=?,kids=?,maturity=?,locale=?,version=? WHERE account_id=? AND"
                + " id=? AND version=?",
            p.name(),
            p.kids(),
            p.maturity(),
            p.locale(),
            p.version(),
            account,
            p.id(),
            expected)
        == 1;
  }

  public List<Device> devices(UUID account) {
    return jdbc.query(
        "SELECT * FROM device WHERE account_id=? ORDER BY created_at,id",
        (rs, n) -> device(rs),
        account);
  }

  public Optional<Device> installation(UUID account, String installation) {
    return jdbc
        .query(
            "SELECT * FROM device WHERE account_id=? AND installation_id=?",
            (rs, n) -> device(rs),
            account,
            installation)
        .stream()
        .findFirst();
  }

  public Optional<Device> device(UUID account, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM device WHERE account_id=? AND id=?", (rs, n) -> device(rs), account, id)
        .stream()
        .findFirst();
  }

  public void insertDevice(UUID account, Device d) {
    jdbc.update(
        "INSERT INTO device(id,account_id,installation_id,name,codecs,drm,status,version) VALUES"
            + " (?,?,?,?,?,?,'ACTIVE',?)",
        d.id(),
        account,
        d.installationId(),
        d.name(),
        String.join(",", d.codecs()),
        String.join(",", d.drm()),
        d.version());
  }

  public boolean revokeDevice(UUID account, Device d) {
    return jdbc.update(
            "UPDATE device SET status='REVOKED',version=? WHERE account_id=? AND id=? AND version=?"
                + " AND status='ACTIVE'",
            d.version(),
            account,
            d.id(),
            d.version() - 1)
        == 1;
  }

  public void emitRevoked(UUID account, Device d) {
    // The owner device version is the ordering boundary; no PII in event payload.
    var event = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload) VALUES"
            + " (?,?,?,'member.device-revoked.v1',jsonb_build_object('accountId',?::text,'deviceId',?::text,'reason','OWNER_REVOKED'))",
        event,
        d.id(),
        d.version(),
        account,
        d.id());
  }

  private static Profile profile(ResultSet r) throws SQLException {
    return new Profile(
        r.getObject("id", UUID.class),
        r.getString("name"),
        r.getBoolean("kids"),
        r.getInt("maturity"),
        r.getString("locale"),
        r.getLong("version"));
  }

  private static Device device(ResultSet r) throws SQLException {
    return new Device(
        r.getObject("id", UUID.class),
        r.getString("installation_id"),
        r.getString("name"),
        split(r.getString("codecs")),
        split(r.getString("drm")),
        r.getString("status"),
        r.getLong("version"));
  }

  private static List<String> split(String value) {
    return value.isEmpty() ? List.of() : Arrays.asList(value.split(","));
  }
}
