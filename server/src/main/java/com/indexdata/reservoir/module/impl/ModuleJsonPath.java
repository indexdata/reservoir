package com.indexdata.reservoir.module.impl;

import com.indexdata.reservoir.module.Module;
import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.ReadContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

public class ModuleJsonPath implements Module {

  JsonPath jsonPath;
  Vertx vertx;

  @Override
  public Future<Void> initialize(Vertx vertx, CodeModuleEntity entity) {
    this.vertx = vertx;
    String script = entity.getScript();
    if (script == null) {
      return Future.failedFuture("module config must include 'script'");
    }
    jsonPath = JsonPath.compile(script);
    return Future.succeededFuture();
  }

  public ModuleJsonPath() {
  }

  public ModuleJsonPath(Vertx vertx, String script) {
    this.vertx = vertx;
    jsonPath = JsonPath.compile(script);
  }

  @Override
  public Future<JsonObject> execute(String function, JsonObject input) {
    throw new UnsupportedOperationException("only executeAsCollection supported for type=jsonpath");
  }

  @Override
  public Future<Collection<String>> executeAsCollection(String function, JsonObject input) {
    if (jsonPath == null) {
      throw new IllegalStateException("uninitialized");
    }
    if (vertx == null) {
      return Future.failedFuture("vertx not set");
    }
    return vertx.executeBlocking(() -> {
      ReadContext ctx = JsonPath.parse(input.encode());
      Collection<String> keys = new HashSet<>();
      try {
        Object o = ctx.read(jsonPath);
        if (o instanceof String string) {
          keys.add(string);
        } else if (o instanceof List<?> list) {
          for (Object m : list) {
            if (!(m instanceof String)) {
              return keys;
            }
          }
          keys.addAll((List<String>) o);
          // for (Object m : list) {
          //   keys.add((String) m);
          // }
        }
      } catch (PathNotFoundException e) {
        //ignore
      }
      return keys;
    });
  }

  @Override
  public Future<Void> terminate() {
    jsonPath = null;
    return Future.succeededFuture();
  }

  public String toString() {
    return jsonPath != null ? jsonPath.getPath() : super.toString();
  }

}
