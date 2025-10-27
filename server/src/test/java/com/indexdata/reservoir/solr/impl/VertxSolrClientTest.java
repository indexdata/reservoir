package com.indexdata.reservoir.solr.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.indexdata.reservoir.solr.VertxSolrClient;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.MapSolrParams;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

@Testcontainers
@ExtendWith(VertxExtension.class)
public class VertxSolrClientTest {

  private final static String COLLECTION = "col1";

  @Container
  private static final SolrContainer solrContainer = new SolrContainer("solr:9.8.1-slim").withCollection(COLLECTION);

  private static String solrUrl;

  @BeforeAll
  public static void beforeClass() {
    solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
  }

  @AfterAll
  public static void afterClass() throws Exception {
    if (solrContainer != null) {
      solrContainer.stop();
    }
  }

  @Test
  public void addOneDocument(Vertx vertx, VertxTestContext context) {
    final UUID docId = UUID.randomUUID();

    VertxSolrClientSolrj c = new VertxSolrClientSolrj(vertx, solrUrl, COLLECTION);
    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", docId.toString());
    doc.addField("title", "title1");
    Collection<SolrInputDocument> docs = List.of(doc);
    c.add(docs)
        .compose(x -> c.commit())
        .onComplete(context.succeedingThenComplete());
  }

  @Test
  public void queryOneDocument(Vertx vertx, VertxTestContext context) {
    final UUID docId = UUID.randomUUID();
    VertxSolrClientSolrj c = new VertxSolrClientSolrj(vertx, solrUrl, COLLECTION);
    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", docId.toString());
    doc.addField("title", List.of("title2a", "title2b"));
    Collection<SolrInputDocument> docs = List.of(doc);
    Map<String,String> map = Map.of("q", "id:" + docId, "fl", "id,title", "sort", "id asc");
    MapSolrParams params = new MapSolrParams(map);
    c.add(docs)
        .compose(x -> c.commit())
        .compose(x -> c.query(params))
        .onComplete(context.succeeding(res -> {
          SolrDocumentList results = res.getResults();
          assertThat(results.getNumFound(), is(1L));
          assertThat(results.get(0).get("id"), is(docId.toString()));
          assertThat(results.get(0).get("title"), is(List.of("title2a", "title2b")));
          context.completeNow();
        }));
  }

  @Test
  public void addOneJsonDocumentSolrj(Vertx vertx, VertxTestContext context) {
    final UUID docId = UUID.randomUUID();
    VertxSolrClient c = new VertxSolrClientSolrj(vertx, solrUrl, COLLECTION);
    JsonArray docs = new JsonArray()
        .add(new JsonObject()
            .put("id", docId.toString())
            .put("title", new JsonArray().add("title3a").add("title3b")));
    Map<String,String> map = Map.of("q", "id:" + docId, "fl", "id,title", "sort", "id asc");
    c.add(docs)
        .compose(x -> c.commit())
        .compose(x -> c.query(map))
        .onComplete(context.succeeding(res -> {
          assertThat(res.getJsonObject("response").getInteger("numFound"), is(1));
          context.completeNow();
        }));
  }

  @Test
  public void commitSolrj(Vertx vertx, VertxTestContext context) {
    VertxSolrClient c = new VertxSolrClientSolrj(vertx, solrUrl, COLLECTION);
    c.commit()
        .onComplete(context.succeedingThenComplete());
  }

  @Test
  public void searchWebClient(Vertx vertx, VertxTestContext context) {
    final UUID docId1 = UUID.randomUUID();
    final UUID docId2 = UUID.randomUUID();
    VertxSolrClient c = VertxSolrClient.create(vertx, solrUrl, COLLECTION);
    JsonArray docs = new JsonArray()
        .add(new JsonObject()
            .put("id", docId1.toString())
            .put("title", new JsonArray().add("title3a").add("title3b")))
        .add(new JsonObject()
            .put("id", docId2.toString())
            .put("title", new JsonArray().add("title4a").add("title4b")));
    Map<String,String> map = Map.of("q", "id:" + docId2, "fl", "id,title", "sort", "id asc");
    c.add(docs)
        .compose(x -> c.commit())
        .compose(x -> c.query(map))
        .onComplete(context.succeeding(res -> {
          JsonObject response = res.getJsonObject("response");
          assertThat(response.getInteger("numFound"), is(1));
          // check we get 2nd document exactly
          assertThat(response.getJsonArray("docs").getJsonObject(0), is(docs.getJsonObject(1)));
          context.completeNow();
        }));
  }
}
