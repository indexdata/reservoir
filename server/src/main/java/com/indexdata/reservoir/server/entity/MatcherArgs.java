package com.indexdata.reservoir.server.entity;

/** Controls what part of a global record is passed into a pool's matcher modules. */
public enum MatcherArgs {
  PAYLOAD("payload"),
  FULL("full");

  private final String value;

  MatcherArgs(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  /** Parse matcher args mode from its wire/database value. */
  public static MatcherArgs fromValue(String value) {
    if (value == null) {
      return null;
    }
    return switch (value) {
      case "payload" -> PAYLOAD;
      case "full" -> FULL;
      default -> throw new IllegalArgumentException("Unknown PoolConfig args value: " + value);
    };
  }
}
