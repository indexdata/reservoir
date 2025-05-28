package org.folio.reservoir.server.entity;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ClusterBuilder {

  public static final String GLOBAL_ID_LABEL = "globalId";
  public static final String LOCAL_ID_LABEL = "localId";
  public static final String SOURCE_ID_LABEL = "sourceId";
  public static final String SOURCE_VERSION_LABEL = "sourceVersion";
  public static final String PAYLOAD_LABEL = "payload";
  public static final String CLUSTER_ID_LABEL = "clusterId";
  public static final String DATESTAMP_LABEL = "datestamp";
  public static final String MATCH_VALUES_LABEL = "matchValues";
  public static final String RECORDS_LABEL = "records";

  private final JsonObject clusterJson = new JsonObject();

  public ClusterBuilder(UUID clusterId) {
    clusterJson.put(CLUSTER_ID_LABEL, clusterId.toString());
  }

  /**
   * Set datestamp for cluster.
   * @param datestamp date stamp in ISO date time format
   * @return this
   */
  public ClusterBuilder datestamp(LocalDateTime datestamp) {
    clusterJson.put(DATESTAMP_LABEL,
        datestamp.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME));
    return this;
  }

  /**
   * Set records from a RowSet.
   * @param rows row set
   * @return this
   */
  public ClusterBuilder records(RowSet<Row> rows) {
    JsonArray records = new JsonArray();
    rows.forEach(row -> records.add(encodeRecord(row)));
    return records(records);
  }

  /**
   * Set records from JsonArray.
   * @param records these are modified (sorted)
   * @return this
   */
  public ClusterBuilder records(JsonArray records) {
    latest(records);
    sort(records);
    clusterJson.put(RECORDS_LABEL, records);
    return this;
  }

  /**
   * For each source, keep the latest version.
   * @param records global records array which is modified by this method
   */
  static void latest(JsonArray records) {
    Map<String,Integer> latestVersion = new HashMap<>();
    int i;
    for (i = 0; i < records.size(); i++) {
      JsonObject a = records.getJsonObject(i);
      String sourceId = a.getString(ClusterBuilder.SOURCE_ID_LABEL);
      int v = a.getInteger(ClusterBuilder.SOURCE_VERSION_LABEL, 0);
      Integer existing = latestVersion.get(sourceId);
      if (existing == null || v > existing) {
        latestVersion.put(sourceId, v);
      }
    }
    i = 0;
    while (i < records.size()) {
      JsonObject a = records.getJsonObject(i);
      String sourceId = a.getString(ClusterBuilder.SOURCE_ID_LABEL);
      int v = a.getInteger(ClusterBuilder.SOURCE_VERSION_LABEL, 0);
      if (latestVersion.get(sourceId) != v) {
        records.remove(i);
        i = 0;
      } else {
        i++;
      }
    }
  }

  static void sort(JsonArray records) {
    ((List<JsonObject>) records.getList())
        .sort(Comparator.comparing((JsonObject a) -> a.getString(ClusterBuilder.SOURCE_ID_LABEL))
            .thenComparingInt(a -> a.getInteger(ClusterBuilder.SOURCE_VERSION_LABEL, 0))
            .thenComparing(a -> a.getString(ClusterBuilder.LOCAL_ID_LABEL)));
  }

  /**
   * Add matchValues from a RowSet.
   * @param rows row set
   * @return this
   */
  public ClusterBuilder matchValues(RowSet<Row> rows) {
    JsonArray matchValues = new JsonArray();
    rows.forEach(row -> matchValues.add(row.getString("match_value")));
    clusterJson.put(MATCH_VALUES_LABEL, matchValues);
    return this;
  }

  public JsonObject build() {
    return clusterJson;
  }

  /**
   * Encodes a single global record row as JSON.
   * @param row global record row
   * @return JSON encoding
   */
  public static JsonObject encodeRecord(Row row) {
    return new JsonObject()
      .put(GLOBAL_ID_LABEL, row.getUUID("id"))
      .put(LOCAL_ID_LABEL, row.getString("local_id"))
      .put(SOURCE_ID_LABEL, row.getString("source_id"))
      .put(SOURCE_VERSION_LABEL, row.getInteger("source_version"))
      .put(PAYLOAD_LABEL, row.getJsonObject(PAYLOAD_LABEL));
  }
}
