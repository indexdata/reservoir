package com.indexdata.reservoir.server.entity;

import io.vertx.core.json.JsonObject;

/**
 * Explicit, reflection-free JSON codecs for {@link OaiPmhStatus}.
 * Separates DB JSONB encoding (numeric millisecond {@code lastRunningTime}, no {@code config})
 * from API status encoding (human-readable {@code lastRunningTime} plus {@code config}).
 */
public final class OaiPmhStatusJson {

  private OaiPmhStatusJson() { }

  /** Decode an OaiPmhStatus from the persisted DB JSONB payload. */
  public static OaiPmhStatus fromDbJson(JsonObject json) {
    if (json == null) {
      return OaiPmhStatus.initialIdle();
    }
    return new OaiPmhStatus(
        OaiPmhJobStatus.fromValue(json.getString("status")),
        json.getString("error"),
        JsonCodec.parseUtcDateTime(json.getString("lastActiveTimestamp")),
        JsonCodec.parseUtcDateTime(json.getString("lastStartedTimestamp")),
        json.getLong("lastRunningTime"),
        json.getLong("lastRecsPerSec"),
        json.getLong("lastTotalRecords"),
        json.getLong("totalDeleted"),
        json.getLong("totalInserted"),
        json.getLong("totalUpdated"),
        json.getLong("totalRecords"),
        json.getInteger("totalRequests"));
  }

  /** Encode an OaiPmhStatus for DB JSONB storage. */
  public static JsonObject toDbJson(OaiPmhStatus status) {
    JsonObject json = new JsonObject();
    JsonCodec.putIfPresent(json, "error", status.error());
    JsonCodec.putUtcDateTime(json, "lastActiveTimestamp", status.lastActiveTimestamp());
    JsonCodec.putIfPresent(json, "lastRecsPerSec", status.lastRecsPerSec());
    JsonCodec.putUtcDateTime(json, "lastStartedTimestamp", status.lastStartedTimestamp());
    JsonCodec.putIfPresent(json, "lastRunningTime", status.lastRunningTimeMs());
    JsonCodec.putIfPresent(json, "lastTotalRecords", status.lastTotalRecords());
    if (status.status() != null) {
      json.put("status", status.status().value());
    }
    JsonCodec.putIfPresent(json, "totalDeleted", status.totalDeleted());
    JsonCodec.putIfPresent(json, "totalInserted", status.totalInserted());
    JsonCodec.putIfPresent(json, "totalUpdated", status.totalUpdated());
    JsonCodec.putIfPresent(json, "totalRecords", status.totalRecords());
    JsonCodec.putIfPresent(json, "totalRequests", status.totalRequests());
    return json;
  }

  /** Encode an OaiPmhStatus with human-readable duration and config for HTTP status responses. */
  public static JsonObject toApiJson(OaiPmhStatus status, JsonObject config) {
    JsonObject json = toDbJson(status);
    json.put("config", config);
    if (status.lastRunningTimeMs() != null) {
      json.put("lastRunningTime", formatDuration(status.lastRunningTimeMs()));
    }
    return json;
  }

  /** Format millisecond duration into a human-readable string. */
  public static String formatDuration(long millis) {
    long seconds = millis / 1000L;
    return String.format("%d days %02d hrs %02d mins %02d secs",
        seconds / (24 * 3600),
        (seconds % (24 * 3600)) / 3600,
        (seconds % 3600) / 60,
        seconds % 60);
  }
}
