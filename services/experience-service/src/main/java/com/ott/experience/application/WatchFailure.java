package com.ott.experience.application;

public class WatchFailure extends RuntimeException {
  private final int status;
  private final String code;

  public WatchFailure(int status, String code) {
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
