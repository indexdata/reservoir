package org.folio.reservoir.solr.impl;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.MapSolrParams;
import org.folio.reservoir.solr.VertxSolrClient;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.testcontainers.containers.SolrContainer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@RunWith(VertxUnitRunner.class)
public class VertxSolrClientTest {

  private final static String COLLECTION = "col1";

  @ClassRule
  public static SolrContainer solrContainer = new SolrContainer("solr:9.8.1-slim")
      .withCollection(COLLECTION);

  private static Vertx vertx;

  private static String solrUrl;

  @BeforeClass
  public static void beforeClass() {
    vertx = Vertx.vertx();
    solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
  }

  @AfterClass
  public static void afterClass(TestContext context) {
    vertx.close(context.asyncAssertSuccess());
  }

  @Test
  public void addOneDocument(TestContext context) {
    final UUID docId = UUID.randomUUID();

    VertxSolrClientSolrj c = new VertxSolrClientSolrj(vertx, solrUrl, COLLECTION);
    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", docId.toString());
    doc.addField("title", "title1");
    Collection<SolrInputDocument> docs = List.of(doc);
    c.add(docs)
        .compose(x -> c.commit())
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void queryOneDocument(TestContext context) {
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
        .onComplete(context.asyncAssertSuccess(res -> {
          SolrDocumentList results = res.getResults();
          assertThat(results.getNumFound(), is(1L));
          assertThat(results.get(0).get("id"), is(docId.toString()));
          assertThat(results.get(0).get("title"), is(List.of("title2a", "title2b")));
        }));
  }

  @Test
  public void addOneJsonDocumentSolrj(TestContext context) {
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
        .onComplete(context.asyncAssertSuccess(res -> {
          assertThat(res.getJsonObject("response").getInteger("numFound"), is(1));
        }));
  }

  @Test
  public void commitSolrj(TestContext context) {
    VertxSolrClient c = new VertxSolrClientSolrj(vertx, solrUrl, COLLECTION);
    c.commit()
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void searchWebClient(TestContext context) {
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
        .onComplete(context.asyncAssertSuccess(res -> {
          JsonObject response = res.getJsonObject("response");
          assertThat(response.getInteger("numFound"), is(1));
          // check we get 2nd document exactly
          assertThat(response.getJsonArray("docs").getJsonObject(0), is(docs.getJsonObject(1)));
        }));
  }
}
