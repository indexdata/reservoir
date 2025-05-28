package org.folio.reservoir.solr;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import org.folio.reservoir.solr.impl.VertxSolrWebClient;

public interface VertxSolrClient {

  static VertxSolrClient create(Vertx vertx, String url, String collection) {
    return new VertxSolrWebClient(vertx, url, collection);
  }

  Future<JsonObject> add(JsonArray docs);

  Future<JsonObject> query(Map<String, String> map);

  Future<JsonObject> commit();

}
