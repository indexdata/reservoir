package com.indexdata.reservoir.server.entity;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * Explicit JSON codec for {@link PoolInitJob}.
 */
public final class PoolInitJobJson {

  private PoolInitJobJson() { }

  /** Encode a pool initialization job to JSON. */
  public static JsonObject toJson(PoolInitJob job) {
    JsonObject json = new JsonObject()
        .put("id", job.id().toString())
        .put("poolId", job.poolId())
        .put("status", job.status().value())
        .put("totalRecords", job.totalRecords());
    JsonCodec.putInstant(json, "startedAt", job.startedAt());
    JsonCodec.putInstant(json, "completedAt", job.completedAt());
    JsonCodec.putIfPresent(json, "error", job.error());
    return json;
  }

  /** Encode a list of pool initialization jobs to a JSON array. */
  public static JsonArray toJsonArray(List<PoolInitJob> jobs) {
    JsonArray array = new JsonArray();
    for (PoolInitJob job : jobs) {
      array.add(toJson(job));
    }
    return array;
  }
}
