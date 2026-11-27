package com.indexdata.reservoir.server.entity;

/** Lifecycle state of an OAI-PMH harvesting job. */
public enum OaiPmhJobStatus {
  IDLE("idle"),
  RUNNING("running");

  private final String value;

  OaiPmhJobStatus(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  /** Parse job status from its wire/database value. */
  public static OaiPmhJobStatus fromValue(String value) {
    if (value == null) {
      return null;
    }
    return switch (value) {
      case "idle" -> IDLE;
      case "running" -> RUNNING;
      default -> throw new IllegalArgumentException("Unknown OaiPmhJobStatus: " + value);
    };
  }
}
