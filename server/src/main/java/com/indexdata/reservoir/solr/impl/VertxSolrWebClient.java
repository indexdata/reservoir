package org.folio.reservoir.solr.impl;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import java.util.Map;
import org.folio.reservoir.solr.VertxSolrClient;

public class VertxSolrWebClient implements VertxSolrClient {

  final Vertx vertx;

  final String url;

  final String collection;

  final WebClient webClient;

  /**
   * Construct Solr client based on WebClient.
   * @param vertx Vert.x handle
   * @param url Solr URL
   * @param collection collection to use
   */
  public VertxSolrWebClient(Vertx vertx, String url, String collection) {
    this.vertx = vertx;
    this.url = url;
    this.collection = collection;
    this.webClient = WebClient.create(vertx);
  }

  @Override
  public Future<JsonObject> add(JsonArray docs) {
    return webClient.postAbs(url + "/" + collection + "/update")
        .addQueryParam("wt", "json")
        .expect(ResponsePredicate.SC_OK)
        .expect(ResponsePredicate.JSON)
        .sendJson(docs)
        .map(HttpResponse::bodyAsJsonObject);
  }

  @Override
  public Future<JsonObject> query(Map<String, String> map) {
    HttpRequest<Buffer> request = webClient.getAbs(url + "/" + collection + "/select")
        .expect(ResponsePredicate.SC_OK)
        .expect(ResponsePredicate.JSON);
    request.queryParams().addAll(map);
    return request
        .addQueryParam("wt", "json")
        .send()
        .map(HttpResponse::bodyAsJsonObject);
  }

  @Override
  public Future<JsonObject> commit() {
    return webClient.postAbs(url + "/" + collection + "/update")
        .expect(ResponsePredicate.SC_OK)
        .expect(ResponsePredicate.JSON)
        .addQueryParam("wt", "json")
        .addQueryParam("commit", "true")
        .send()
        .map(HttpResponse::bodyAsJsonObject);
  }
}
