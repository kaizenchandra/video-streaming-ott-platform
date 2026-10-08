package com.ott.media.application;

public class MediaFailure extends RuntimeException {
  private final int status;
  private final String code;

  public MediaFailure(int status, String code) {
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
