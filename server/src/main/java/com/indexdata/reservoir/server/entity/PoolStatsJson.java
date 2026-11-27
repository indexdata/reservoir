package com.indexdata.reservoir.server.entity;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.UUID;

/**
 * Explicit JSON codec for {@link PoolStats}.
 */
public final class PoolStatsJson {

  private PoolStatsJson() { }

  /** Encode pool statistics to JSON. */
  public static JsonObject toJson(PoolStats stats) {
    JsonObject matchValuesPer = new JsonObject();
    stats.matchValuesPerCluster().forEach((k, v) ->
        matchValuesPer.put(Integer.toString(k), v));

    JsonObject recordsPer = new JsonObject();
    stats.recordsPerCluster().forEach((k, v) ->
        recordsPer.put(Integer.toString(k), v));

    JsonObject clusterSamplePer = new JsonObject();
    stats.recordsPerClusterSample().forEach((k, ids) -> {
      JsonArray samples = new JsonArray();
      for (UUID id : ids) {
        samples.add(id.toString());
      }
      clusterSamplePer.put(Integer.toString(k), samples);
    });

    return new JsonObject()
        .put("recordsTotal", stats.recordsTotal())
        .put("clustersTotal", stats.clustersTotal())
        .put("matchValuesPerCluster", matchValuesPer)
        .put("recordsPerCluster", recordsPer)
        .put("recordsPerClusterSample", clusterSamplePer);
  }
}
