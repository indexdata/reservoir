package com.indexdata.reservoir.server.entity;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Explicit JSON codec for {@link Cluster}.
 */
public final class ClusterJson {

  public static final String CLUSTER_ID = "clusterId";
  public static final String DATESTAMP = "datestamp";
  public static final String MATCH_VALUES = "matchValues";
  public static final String RECORDS = "records";

  private ClusterJson() { }

  /** Encode a cluster to JSON. */
  public static JsonObject toJson(Cluster cluster) {
    JsonObject json = new JsonObject()
        .put(CLUSTER_ID, cluster.clusterId().toString());
    JsonCodec.putUtcDateTime(json, DATESTAMP, cluster.datestamp());
    JsonArray records = new JsonArray();
    for (GlobalRecord record : cluster.records()) {
      records.add(GlobalRecordJson.toJson(record));
    }
    json.put(RECORDS, records);
    if (cluster.matchValues() != null) {
      JsonArray values = new JsonArray();
      for (String value : cluster.matchValues()) {
        values.add(value);
      }
      json.put(MATCH_VALUES, values);
    }
    return json;
  }
}
