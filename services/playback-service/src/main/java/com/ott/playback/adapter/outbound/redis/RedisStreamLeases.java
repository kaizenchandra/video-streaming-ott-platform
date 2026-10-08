package com.ott.playback.adapter.outbound.redis;

import com.ott.playback.application.StreamLeases;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisStreamLeases implements StreamLeases {
  private final StringRedisTemplate redis;
  private static final DefaultRedisScript<Long> ACQUIRE =
      new DefaultRedisScript<>(
          """
          local t=redis.call('TIME');local now=t[1]*1000+math.floor(t[2]/1000)
          redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now)
          if redis.call('ZSCORE',KEYS[1],ARGV[1]) then return 1 end
          if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[2]) then return 0 end
          redis.call('ZADD',KEYS[1],now+tonumber(ARGV[3]),ARGV[1]);redis.call('PEXPIRE',KEYS[1],180000);return 1
          """,
          Long.class);
  private static final DefaultRedisScript<Long> RENEW =
      new DefaultRedisScript<>(
          """
          local t=redis.call('TIME');local now=t[1]*1000+math.floor(t[2]/1000)
          local old=redis.call('ZSCORE',KEYS[1],ARGV[1])
          if not old or tonumber(old)<=now then return 0 end
          redis.call('ZADD',KEYS[1],now+tonumber(ARGV[2]),ARGV[1]);redis.call('PEXPIRE',KEYS[1],180000);return 1
          """,
          Long.class);

  public RedisStreamLeases(StringRedisTemplate redis) {
    this.redis = redis;
  }

  private String key(UUID account) {
    return "playback:lease:{" + account + "}";
  }

  public boolean acquire(UUID account, UUID session, int limit, long duration) {
    return Long.valueOf(1)
        .equals(
            redis.execute(
                ACQUIRE,
                List.of(key(account)),
                session.toString(),
                Integer.toString(limit),
                Long.toString(duration)));
  }

  public boolean renew(UUID account, UUID session, long duration) {
    return Long.valueOf(1)
        .equals(
            redis.execute(
                RENEW, List.of(key(account)), session.toString(), Long.toString(duration)));
  }

  public void release(UUID account, UUID session) {
    redis.opsForZSet().remove(key(account), session.toString());
  }
}
