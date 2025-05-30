package com.indexdata.reservoir.solr.impl;

import com.indexdata.reservoir.solr.VertxSolrClient;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpResponseExpectation;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.Map;

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
        .sendJson(docs)
        .expecting(HttpResponseExpectation.SC_SUCCESS)
        .expecting(HttpResponseExpectation.JSON)
        .map(HttpResponse::bodyAsJsonObject);
  }

  @Override
  public Future<JsonObject> query(Map<String, String> map) {
    HttpRequest<Buffer> request = webClient.getAbs(url + "/" + collection + "/select");
    request.queryParams().addAll(map);
    return request
        .addQueryParam("wt", "json")
        .send()
        .expecting(HttpResponseExpectation.SC_SUCCESS)
        .expecting(HttpResponseExpectation.JSON)
        .map(HttpResponse::bodyAsJsonObject);
  }

  @Override
  public Future<JsonObject> commit() {
    return webClient.postAbs(url + "/" + collection + "/update")
        .addQueryParam("wt", "json")
        .addQueryParam("commit", "true")
        .send()
        .expecting(HttpResponseExpectation.SC_SUCCESS)
        .expecting(HttpResponseExpectation.JSON)
        .map(HttpResponse::bodyAsJsonObject);
  }
}
