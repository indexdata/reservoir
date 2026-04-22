package com.indexdata.reservoir.module.impl;

import com.indexdata.reservoir.module.Module;
import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.ReadContext;
import io.vertx.core.json.JsonObject;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

public class ModuleJsonPath implements Module {

  JsonPath jsonPath;

  @Override
  public void initialize(CodeModuleEntity entity) {
    String script = entity.getScript();
    if (script == null) {
      throw new IllegalArgumentException("module config must include 'script'");
    }
    jsonPath = JsonPath.compile(script);
  }

  public ModuleJsonPath() {
  }

  public ModuleJsonPath(String script) {
    jsonPath = JsonPath.compile(script);
  }

  @Override
  public JsonObject execute(String function, JsonObject input) {
    throw new UnsupportedOperationException("only executeAsCollection supported for type=jsonpath");
  }

  @Override
  public Collection<String> executeAsCollection(String function, JsonObject input) {
    if (jsonPath == null) {
      throw new IllegalStateException("uninitialized");
    }
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
      }
    } catch (PathNotFoundException e) {
      //ignore
    }
    return keys;
  }

  @Override
  public void terminate() {
    jsonPath = null;
  }

  public String toString() {
    return jsonPath != null ? jsonPath.getPath() : super.toString();
  }

}
