package org.folio.reservoir.matchkey.impl;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import java.util.Collection;
import org.folio.okapi.common.WebClientFactory;
import org.folio.reservoir.matchkey.MatchKeyMethod;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * Don't use this.
 * @deprecated for removal
 */
@Deprecated(forRemoval = true, since = "1.0")
public class MatchKeyJavaScript implements MatchKeyMethod {

  Value getKeysFunction;
  Context context;

  Future<Value> evalUrl(Vertx vertx, String url) {
    WebClient webClient = WebClientFactory.getWebClient(vertx);
    String moduleName = url.substring(url.lastIndexOf("/") + 1);
    return webClient.getAbs(url)
        .expect(ResponsePredicate.SC_OK)
        .send()
        .map(response -> context.eval(Source
          .newBuilder("js", response.bodyAsString(), moduleName)
          .buildLiteral()));
  }

  @Override
  public Future<Void> configure(Vertx vertx, JsonObject configuration) {
    String url = configuration.getString("url");
    String script = configuration.getString("script");
    if (url == null && script == null) {
      return Future.failedFuture(
        new IllegalArgumentException("javascript: url or script must be given"));
    }
    Future<Void> future = Future.succeededFuture();
    if (url != null) {
      // if url is specified and ends with mjs, assume it is a ES module that exports a
      // 'matchkey' function, otherwise treat it like a regular script
      final boolean isModule = url.endsWith("mjs");
      if (isModule) {
        context = Context.newBuilder("js")
            .allowExperimentalOptions(true)
            .option("js.esm-eval-returns-exports", "true")
            .build();
      } else {
        context = Context.create("js");
      }
      future = evalUrl(vertx, url)
          .map(value -> getKeysFunction = isModule ? value.getMember("matchkey") : value)
          .mapEmpty();
    }
    // if script is specified, we treat it as a regular, non-module JS file which
    // evaluates to a function that accepts an object and returns an array of strings
    if (script != null) {
      if (context == null) {
        context = Context.create("js");
      }
      future = future
          .map(v -> getKeysFunction = context.eval("js", script))
          .mapEmpty();
    }
    return future;

  }

  private void addValue(Collection<String> keys, Value value) {
    if (value.isNumber()) {
      keys.add(Long.toString(value.asLong()));
    } else if (value.isString()) {
      keys.add(value.asString());
    }
  }

  @Override
  public void getKeys(JsonObject payload, Collection<String> keys) {
    Value value = getKeysFunction.execute(payload.encode());
    if (value.hasArrayElements()) {
      for (int i = 0; i < value.getArraySize(); i++) {
        Value memberValue = value.getArrayElement(i);
        addValue(keys, memberValue);
      }
    } else {
      addValue(keys, value);
    }
  }

  @Override
  public void close() {
    if (context != null) {
      context.close(true);
      context = null;
    }
  }
}
