package com.indexdata.reservoir.server;

import com.indexdata.reservoir.module.ModuleExecutable;
import com.indexdata.reservoir.server.entity.Cluster;
import com.indexdata.reservoir.server.entity.ClusterJson;
import com.indexdata.reservoir.server.entity.GlobalRecord;
import com.indexdata.reservoir.util.JsonToMarcXml;
import com.indexdata.reservoir.util.MarcInJsonUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class ClusterMarcXml {
  private ClusterMarcXml() {}

  /**
   * Construct metadata record XML string from a typed {@link Cluster} without mutating the
   * cluster's underlying MARC payloads.
   *
   * <p>999 ind1=1 ind2=0 has identifiers for the record. $i cluster UUID; multiple $m for each
   * match value; Multiple $l, $s, $v tuples for local identifier, source identifier, and version.
   *
   * @param cluster cluster domain object
   * @return metadata record string; null if deleted (no records)
   */
  static String getMetadataJava(Cluster cluster) {
    JsonArray identifiersField = new JsonArray();
    identifiersField.add(new JsonObject().put("i", cluster.clusterId().toString()));
    if (cluster.matchValues() != null) {
      for (String matchValue : cluster.matchValues()) {
        identifiersField.add(new JsonObject().put("m", matchValue));
      }
    }
    JsonObject combinedMarc = null;
    for (GlobalRecord clusterRecord : cluster.records()) {
      JsonObject thisMarc = clusterRecord.payload().getJsonObject("marc");
      JsonArray f999 = MarcInJsonUtil.lookupMarcDataField(thisMarc, "999", " ", " ");
      if (combinedMarc == null) {
        combinedMarc = thisMarc.copy();
      } else {
        JsonArray c999 = MarcInJsonUtil.lookupMarcDataField(combinedMarc, "999", " ", " ");
        if (f999 != null && c999 != null) {
          c999.addAll(f999.copy());
        }
      }
      identifiersField.add(new JsonObject().put("l", clusterRecord.localId()));
      identifiersField.add(new JsonObject().put("s", clusterRecord.sourceId()));
      identifiersField.add(new JsonObject()
          .put("v", Integer.toString(clusterRecord.sourceVersion())));
    }
    if (combinedMarc == null) {
      return null; // a deleted record
    }
    MarcInJsonUtil.createMarcDataField(combinedMarc, "999", "1", "0").addAll(identifiersField);
    return JsonToMarcXml.convert(combinedMarc);
  }

  /**
   * Get MARC XML for a cluster.
   *
   * @param cluster domain cluster (null or empty records means deleted)
   * @param transformer optional JS transformer executable
   * @param vertx Vertx
   * @return Future with MARC XML string (or null for deleted)
   */
  public static Future<String> getClusterMarcXml(Cluster cluster, ModuleExecutable transformer,
      Vertx vertx) {
    if (cluster == null || cluster.records().isEmpty()) {
      return Future.succeededFuture(null); // deleted record
    }
    if (transformer == null) {
      return Future.succeededFuture(getMetadataJava(cluster));
    }
    return transformer.execute(ClusterJson.toJson(cluster)).map(JsonToMarcXml::convert);
  }
}
