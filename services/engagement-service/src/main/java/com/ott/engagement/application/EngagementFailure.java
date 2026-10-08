package com.ott.engagement.application;

public class EngagementFailure extends RuntimeException {
  private final int status;
  private final String code;

  public EngagementFailure(int status, String code) {
    super(code.replace('_', ' '));
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
