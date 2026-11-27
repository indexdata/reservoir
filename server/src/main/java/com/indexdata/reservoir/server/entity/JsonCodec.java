package com.indexdata.reservoir.server.entity;

import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Shared helpers for explicit, null-omitting, reflection-free JSON codecs. */
public final class JsonCodec {

  private JsonCodec() { }

  /** Put key/value into json only when value is non-null. */
  public static void putIfPresent(JsonObject json, String key, Object value) {
    if (value != null) {
      json.put(key, value);
    }
  }

  /** Put an ISO-8601 instant into json when non-null. */
  public static void putInstant(JsonObject json, String key, Instant value) {
    if (value != null) {
      json.put(key, value.toString());
    }
  }

  /** Put a UTC ISO-8601 timestamp into json when non-null. */
  public static void putUtcDateTime(JsonObject json, String key, LocalDateTime value) {
    if (value != null) {
      json.put(key, formatUtcDateTime(value));
    }
  }

  public static String formatUtcDateTime(LocalDateTime value) {
    return value == null ? null
        : value.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME);
  }

  public static LocalDateTime parseUtcDateTime(String value) {
    return value == null ? null
        : LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME);
  }

  public static Instant toUtcInstant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
