package com.indexdata.reservoir.module.impl;

import com.indexdata.reservoir.module.Module;
import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import org.folio.okapi.common.WebClientFactory;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

@SuppressWarnings({ "squid:S5738", "squid:S1123" })
public class ModuleJavaScript implements Module {
  private String id;
  @Deprecated(forRemoval = true, since = "1.0")
  private String defaultFunctionName;
  private Value module;
  private Value function;
  private Context context;
  private Vertx vertx;

  @Override
  public Future<Void> initialize(Vertx vertx, CodeModuleEntity entity) {
    id = entity.getId();
    if (id == null || id.isEmpty()) {
      return Future.failedFuture(
          new IllegalArgumentException("Module config must include 'id'"));
    }
    this.vertx = vertx;
    String url = entity.getUrl();
    String script = entity.getScript();
    if (url != null && !url.isEmpty()) {
      // url always points to an ES module
      defaultFunctionName = entity.getFunction();
      final boolean isModule = url.endsWith("mjs");
      if (!isModule) {
        return Future.failedFuture(new IllegalArgumentException(
            "url must end with .mjs to designate ES module"));
      }
      Context.Builder cb = Context.newBuilder("js")
          .allowExperimentalOptions(true)
          .option("js.esm-eval-returns-exports", "true");
      context = cb.build();
      return evalUrl(vertx, url)
          .map(value -> module = value)
          .mapEmpty();
      // if script is specified, we treat it as a regular JS script that evaluates to
      // a function
    } else if (script != null && !script.isEmpty()) {
      context = Context.create("js");
      function = context.eval("js", script);
      return Future.succeededFuture();
    } else {
      return Future.failedFuture(
          new IllegalArgumentException("Module config must include 'url' or 'script'"));
    }
  }

  private Future<Value> evalUrl(Vertx vertx, String url) {
    WebClient webClient = WebClientFactory.getWebClient(vertx);
    String moduleName = url.substring(url.lastIndexOf("/") + 1);

    return webClient.getAbs(url)
        .send()
        .compose(response -> {
          if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return Future.succeededFuture(context.eval(Source
                .newBuilder("js", response.bodyAsString(), moduleName)
                .buildLiteral()));
          } else {
            return Future.failedFuture(new IOException(
                String.format("Config error: cannot retrieve module '%s' at %s (%d)",
                    id, url, response.statusCode())));
          }
        });
  }

  private Value getFunction(String functionName) {
    if (module != null) {
      if (functionName == null) {
        if (defaultFunctionName == null) {
          throw new IllegalArgumentException(
              "JS url modules require 'function' defined in config or by caller");
        }
        functionName = defaultFunctionName;
      }
      Value v = module.getMember(functionName);
      if (v == null || !v.canExecute()) {
        throw new IllegalArgumentException(
            "Module " + id + " does not include function " + functionName);
      }
      return v;
    } else if (function != null) {
      return function;
    } else {
      throw new IllegalStateException("uninitialized");
    }
  }

  private Value execJavaScript(String functionName, JsonObject input) {
    return getFunction(functionName).execute(input.encode());
  }

  @Override
  public Future<JsonObject> execute(String functionName, JsonObject input) {
    return vertx.executeBlocking(() -> {
      var output = execJavaScript(functionName, input);
      // only support string encoded JSON objects for now
      if (!output.isString()) {
        throw new IllegalArgumentException(
            "Function " + functionName + " of module " + id + " must return JSON string");
      }
      return new JsonObject(output.asString());
    });
  }

  @Override
  public Future<Collection<String>> executeAsCollection(String functionName, JsonObject input) {
    return vertx.executeBlocking(() -> {
      var output = execJavaScript(functionName, input);
      Collection<String> keys = new HashSet<>();
      if (output.hasArrayElements()) {
        for (int i = 0; i < output.getArraySize(); i++) {
          Value memberValue = output.getArrayElement(i);
          addValue(keys, memberValue);
        }
      } else {
        addValue(keys, output);
      }
      return keys;
    });
  }

  private void addValue(Collection<String> keys, Value value) {
    if (value.isNumber()) {
      keys.add(Long.toString(value.asLong()));
    } else if (value.isString()) {
      keys.add(value.asString());
    }
  }

  @Override
  public Future<Void> terminate() {
    if (context != null) {
      context.close(true);
      context = null;
      module = null;
      function = null;
    }
    return Future.succeededFuture();
  }

}
