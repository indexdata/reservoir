package com.indexdata.reservoir.server.entity;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.vertx.core.json.JsonObject;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClusterTest {

  @Test
  void keepsLatestVersionPerSourceAndSortsDeterministically() {
    UUID clusterId = UUID.randomUUID();
    GlobalRecord bibV10A = new GlobalRecord(
        UUID.randomUUID(), "002", "bib", 10, new JsonObject().put("k", "d"));
    GlobalRecord bibV2 = new GlobalRecord(
        UUID.randomUUID(), "001", "bib", 2, new JsonObject().put("k", "c"));
    GlobalRecord abeV1 = new GlobalRecord(
        UUID.randomUUID(), "001", "abe", 1, new JsonObject().put("k", "b"));
    GlobalRecord bibV10B = new GlobalRecord(
        UUID.randomUUID(), "001", "bib", 10, new JsonObject().put("k", "a"));

    Cluster cluster = Cluster.of(
        clusterId,
        LocalDateTime.of(2025, 1, 2, 3, 4, 5),
        List.of(bibV10A, bibV2, abeV1, bibV10B),
        List.of("mv1"));

    assertThat(cluster.records(), contains(abeV1, bibV10B, bibV10A));
    JsonObject json = ClusterJson.toJson(cluster);
    assertThat(json.getString(ClusterJson.CLUSTER_ID), is(clusterId.toString()));
    assertThat(json.getString(ClusterJson.DATESTAMP), is("2025-01-02T03:04:05Z"));
    assertThat(json.getJsonArray(ClusterJson.RECORDS).size(), is(3));
    assertThat(json.getJsonArray(ClusterJson.MATCH_VALUES).getString(0), is("mv1"));
  }

  @Test
  void omitsNullDatestampAndNullMatchValues() {
    UUID clusterId = UUID.randomUUID();
    GlobalRecord rec = new GlobalRecord(
        UUID.randomUUID(), "001", "src", 1, new JsonObject().put("k", "v"));

    Cluster cluster = Cluster.of(clusterId, null, List.of(rec), null);
    JsonObject json = ClusterJson.toJson(cluster);
    assertThat(json.getString(ClusterJson.CLUSTER_ID), is(clusterId.toString()));
    assertThat(json.getValue(ClusterJson.DATESTAMP), is(nullValue()));
    assertThat(json.getValue(ClusterJson.MATCH_VALUES), is(nullValue()));
    assertThat(json.getJsonArray(ClusterJson.RECORDS).size(), is(1));
  }
}
