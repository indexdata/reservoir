package com.indexdata.reservoir.module;

import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import io.vertx.core.json.JsonObject;
import java.util.Collection;

public interface Module {

  void initialize(CodeModuleEntity entity);

  JsonObject execute(String symbol, JsonObject input);

  Collection<String> executeAsCollection(String symbol, JsonObject input);

  void terminate();

}
