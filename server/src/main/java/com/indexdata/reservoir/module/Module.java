package com.indexdata.reservoir.module;

import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.Collection;

public interface Module {

  Future<CodeModuleEntity> initialize(Vertx vertx, CodeModuleEntity entity);

  Future<JsonObject> execute(String symbol, JsonObject input);

  Future<Collection<String>> executeAsCollection(String symbol, JsonObject input);

  Future<Void> terminate();

}
