package com.ott.operations.application;

public class OperationsFailure extends RuntimeException {
  private final int status;
  private final String code;

  public OperationsFailure(int status, String code) {
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
