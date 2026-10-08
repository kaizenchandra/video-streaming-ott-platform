package com.ott.engagement.adapter.outbound.ads;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CreativePolicy {
  private final Set<String> hosts;

  public CreativePolicy(@Value("${ott.ads.creative-hosts:}") String configured) {
    hosts =
        Arrays.stream(configured.split(","))
            .map(String::trim)
            .map(x -> x.toLowerCase(java.util.Locale.ROOT))
            .filter(x -> !x.isBlank())
            .collect(Collectors.toUnmodifiableSet());
  }

  public boolean permits(String value) {
    if (value == null || value.length() > 2048) return false;
    try {
      URI uri = URI.create(value);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && hosts.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
          && uri.getRawUserInfo() == null
          && uri.getRawFragment() == null
          && (uri.getPort() == -1 || uri.getPort() == 443)
          && uri.getRawPath() != null
          && !uri.getRawPath().contains("..");
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
