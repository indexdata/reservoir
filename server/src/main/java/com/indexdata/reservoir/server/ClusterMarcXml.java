package com.indexdata.reservoir.server;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import com.indexdata.reservoir.module.ModuleExecutable;
import com.indexdata.reservoir.server.entity.ClusterBuilder;
import com.indexdata.reservoir.util.JsonToMarcXml;
import com.indexdata.reservoir.util.MarcInJsonUtil;

public class ClusterMarcXml {
  private ClusterMarcXml() {}

  /**
   * Construct metadata record XML string.
   *
   * <p>999 ind1=1 ind2=0 has identifiers for the record. $i cluster UUID; multiple $m for each
   * match value; Multiple $l, $s pairs for local identifier and source identifiers.
   *
   * <p>999 ind1=0 ind2=0 has holding information. Not complete yet.
   *
   * @param clusterJson ClusterBuilder.build output
   * @return metadata record string; null if it's deleted record
   */
  static String getMetadataJava(JsonObject clusterJson) {
    JsonArray identifiersField = new JsonArray();
    identifiersField.add(new JsonObject()
        .put("i", clusterJson.getString(ClusterBuilder.CLUSTER_ID_LABEL)));
    JsonArray matchValues = clusterJson.getJsonArray(ClusterBuilder.MATCH_VALUES_LABEL);
    if (matchValues != null) {
      for (int i = 0; i < matchValues.size(); i++) {
        String matchValue = matchValues.getString(i);
        identifiersField.add(new JsonObject().put("m", matchValue));
      }
    }
    JsonArray records = clusterJson.getJsonArray("records");
    JsonObject combinedMarc = null;
    for (int i = 0; i < records.size(); i++) {
      JsonObject clusterRecord = records.getJsonObject(i);
      JsonObject thisMarc = clusterRecord.getJsonObject(ClusterBuilder.PAYLOAD_LABEL)
          .getJsonObject("marc");
      JsonArray f999 = MarcInJsonUtil.lookupMarcDataField(thisMarc, "999", " ", " ");
      if (combinedMarc == null) {
        combinedMarc = thisMarc;
      } else {
        JsonArray c999 = MarcInJsonUtil.lookupMarcDataField(combinedMarc, "999", " ", " ");
        // normally we'd have 999 in combined record
        if (f999 != null && c999 != null) {
          c999.addAll(f999); // all 999 in one data field
        }
      }
      identifiersField.add(new JsonObject()
          .put("l", clusterRecord.getString(ClusterBuilder.LOCAL_ID_LABEL)));
      identifiersField.add(new JsonObject()
          .put("s", clusterRecord.getString(ClusterBuilder.SOURCE_ID_LABEL)));
      identifiersField.add(new JsonObject()
          .put("v", clusterRecord.getInteger(ClusterBuilder.SOURCE_VERSION_LABEL).toString()));
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
   * @param cb ClusterBuilder
   * @param transformer ModuleExecutable
   * @param vertx Vertx
   * @return Future with MARC XML string
   */
  public static Future<String> getClusterMarcXml(ClusterBuilder cb, ModuleExecutable transformer,
      Vertx vertx) {

    if (cb == null) {
      return Future.succeededFuture(null); // deleted record
    } else if (transformer == null) {
      return Future.succeededFuture(getMetadataJava(cb.build()));
    }
    return transformer.execute(cb.build()).map(JsonToMarcXml::convert);
  }
}
