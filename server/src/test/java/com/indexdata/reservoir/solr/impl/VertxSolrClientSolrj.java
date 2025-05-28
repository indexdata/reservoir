package org.folio.reservoir.solr.impl;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.folio.reservoir.solr.VertxSolrClient;

/**
 * Vert.x wrapper for Solrj.
 *
 * <p><a href="https://solr.apache.org/guide/solr/latest/deployment-guide/solrj.html">
 *   Solrj documentation</a></p>
 */
public class VertxSolrClientSolrj implements VertxSolrClient {

  final SolrClient solrClient;

  final Vertx vertx;

  final String collection;


  /**
   * Construct Solr client based on solrj.
   * @param vertx Vert.x handle
   * @param url Solr URL
   * @param collection collection to use
   */
  public VertxSolrClientSolrj(Vertx vertx, String url, String collection) {
    this(vertx, new Http2SolrClient.Builder(url).build(), collection);
  }

  /**
   * Construct client based on solrj.
   * @param vertx Vert.x
   * @param solrClient the native SolrClient
   * @param collection collection to run against
   */
  public VertxSolrClientSolrj(Vertx vertx, SolrClient solrClient, String collection) {
    this.solrClient = solrClient;
    this.vertx = vertx;
    this.collection = collection;
  }

  @Override
  public Future<JsonObject> add(JsonArray docs) {
    Collection<SolrInputDocument> solrDocs = new LinkedList<>();
    for (int i = 0; i < docs.size(); i++) {
      SolrInputDocument doc = new SolrInputDocument();
      if (docs.getValue(i) instanceof JsonObject x) {
        x.forEach(e -> doc.addField(e.getKey(), e.getValue()));
      }
      solrDocs.add(doc);
    }
    return add(solrDocs).map(updateResponse -> new JsonObject());
  }

  Future<UpdateResponse> add(Collection<SolrInputDocument> docs) {
    return vertx.executeBlocking(p -> {
      try {
        p.complete(solrClient.add(collection, docs));
      } catch (SolrServerException | IOException e) {
        p.fail(e);
      }
    });
  }

  Future<QueryResponse> query(SolrParams params) {
    return vertx.executeBlocking(p -> {
      try {
        p.complete(solrClient.query(collection, params));
      } catch (SolrServerException | IOException e) {
        p.fail(e);
      }
    });
  }

  @Override
  public Future<JsonObject> query(Map<String, String> map) {
    MapSolrParams params = new MapSolrParams(map);
    return query(params).map(res -> {
      JsonObject ret = new JsonObject();
      SolrDocumentList results = res.getResults();
      ret.put("response", new JsonObject().put("numFound", results.getNumFound()));
      return ret;
    });
  }

  @Override
  public Future<JsonObject> commit() {
    return vertx.executeBlocking(p -> {
      try {
        p.complete(solrClient.commit(collection));
      } catch (SolrServerException | IOException e) {
        p.fail(e);
      }
    }).map(x -> new JsonObject());
  }
}
