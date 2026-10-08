package com.ott.operations.application;

import java.util.Map;
import java.util.UUID;

public interface OwnerControl {
  Map<String, Object> summary(UUID account);

  record Result(long version) {}

  Result apply(UUID operation, UUID account, String action, String reason);
}
