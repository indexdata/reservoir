package com.indexdata.reservoir.server.entity;

/** Controls whether a pool's clusters are updated automatically during ingest or manually. */
public enum UpdateMode {
  INGEST("ingest"),
  MANUAL("manual");

  private final String value;

  UpdateMode(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  /** Parse update mode from its wire/database value. */
  public static UpdateMode fromValue(String value) {
    if (value == null) {
      return null;
    }
    return switch (value) {
      case "ingest" -> INGEST;
      case "manual" -> MANUAL;
      default -> throw new IllegalArgumentException("Unknown PoolConfig update value: " + value);
    };
  }
}
