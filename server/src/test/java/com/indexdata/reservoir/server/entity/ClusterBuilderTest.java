package org.folio.reservoir.server.entity;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.samePropertyValuesAs;

public class ClusterBuilderTest {
  @Test
  public void datestamp() {
    UUID clusterId = UUID.randomUUID();
    ClusterBuilder clusterBuilder = new ClusterBuilder(clusterId);
    String date = "2022-07-13T11:42:00Z";
    clusterBuilder.datestamp(LocalDateTime.parse(date, DateTimeFormatter.ISO_DATE_TIME));
    assertThat(clusterBuilder.build(), is(new JsonObject()
        .put(ClusterBuilder.CLUSTER_ID_LABEL, clusterId.toString())
        .put(ClusterBuilder.DATESTAMP_LABEL, date)));
  }

  @Test
  public void recs() {
    UUID clusterId = UUID.randomUUID();
    ClusterBuilder clusterBuilder = new ClusterBuilder(clusterId);
    JsonArray recs = new JsonArray()
        .add(new JsonObject()
            .put(ClusterBuilder.SOURCE_ID_LABEL, "bib")
            .put(ClusterBuilder.SOURCE_VERSION_LABEL, 2)
            .put(ClusterBuilder.LOCAL_ID_LABEL, "001")
            .put(ClusterBuilder.PAYLOAD_LABEL, "a")
        )
        .add(new JsonObject()
            .put(ClusterBuilder.SOURCE_ID_LABEL, "abe")
            .put(ClusterBuilder.SOURCE_VERSION_LABEL, 1)
            .put(ClusterBuilder.LOCAL_ID_LABEL, "001")
            .put(ClusterBuilder.PAYLOAD_LABEL, "b")
        )
        .add(new JsonObject()
            .put(ClusterBuilder.SOURCE_ID_LABEL, "bib")
            .put(ClusterBuilder.SOURCE_VERSION_LABEL, 1)
            .put(ClusterBuilder.LOCAL_ID_LABEL, "001")
            .put(ClusterBuilder.PAYLOAD_LABEL, "c")
        )
        .add(new JsonObject()
            .put(ClusterBuilder.SOURCE_ID_LABEL, "bib")
            .put(ClusterBuilder.SOURCE_VERSION_LABEL, 2)
            .put(ClusterBuilder.LOCAL_ID_LABEL, "002")
            .put(ClusterBuilder.PAYLOAD_LABEL, "d")

        );
    clusterBuilder.records(recs); // recs modified!!
    JsonObject expected = new JsonObject()
        .put(ClusterBuilder.CLUSTER_ID_LABEL, clusterId.toString())
        .put(ClusterBuilder.RECORDS_LABEL, recs);
    JsonObject clusterJson = clusterBuilder.build();
    assertThat(clusterJson, is(expected));
    JsonArray gotRecs = clusterJson.getJsonArray(ClusterBuilder.RECORDS_LABEL);
    System.out.println(gotRecs.encodePrettily());
    assertThat(gotRecs.getJsonObject(0).getString(ClusterBuilder.PAYLOAD_LABEL), is("b"));
    assertThat(gotRecs.getJsonObject(1).getString(ClusterBuilder.PAYLOAD_LABEL), is("a"));
    assertThat(gotRecs.getJsonObject(2).getString(ClusterBuilder.PAYLOAD_LABEL), is("d"));
  }
}
