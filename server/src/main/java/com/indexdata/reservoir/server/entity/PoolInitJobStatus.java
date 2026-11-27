package com.indexdata.reservoir.server.entity;

/** Lifecycle state of an asynchronous pool initialization job. */
public enum PoolInitJobStatus {
  RUNNING("running"),
  IDLE("idle");

  private final String value;

  PoolInitJobStatus(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  /** Parse status from its persisted string representation. */
  public static PoolInitJobStatus fromValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("PoolInitJobStatus must not be null");
    }
    return switch (value) {
      case "running" -> RUNNING;
      case "idle" -> IDLE;
      default -> throw new IllegalArgumentException("Unknown PoolInitJobStatus: " + value);
    };
  }
}
