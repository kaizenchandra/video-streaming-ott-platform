package com.ott.playback.application;

public class PlaybackFailure extends RuntimeException {
  private final int status;
  private final String code;

  public PlaybackFailure(int status, String code, String detail) {
    super(detail);
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
