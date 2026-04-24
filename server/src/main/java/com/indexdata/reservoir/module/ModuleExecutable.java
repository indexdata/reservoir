package com.indexdata.reservoir.module;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.Collection;

public class ModuleExecutable {
  private final Module module;
  private final ModuleInvocation invocation;
  private final Vertx vertx;

  /**
   * Create module executable by passing it a module and an invocation.
   * @param module  module to be executed
   * @param invocation execution invocation
   * @param vertx Vertx instance to use for executing the module
   */
  public ModuleExecutable(Module module, ModuleInvocation invocation, Vertx vertx) {
    this.invocation = invocation;
    this.module = module;
    this.vertx = vertx;
  }

  /**
   * Execute this module with the given input, returning a future that completes with the result.
   * @param input the input JSON object
   * @return a future that completes with the output JSON object
   */
  public Future<JsonObject> execute(JsonObject input) {
    return vertx.executeBlocking(() ->
      module.execute(invocation.getFunctionName(), input)
    );
  }

  public Collection<String> executeAsCollectionSync(JsonObject input) {
    return module.executeAsCollection(invocation.getFunctionName(), input);
  }

  /**
   * Execute this module as a collection, returning a future that completes with the result.
   * @param input the input JSON object
   * @return a future that completes with the collection of strings
   */
  public Future<Collection<String>> executeAsCollection(JsonObject input) {
    return vertx.executeBlocking(() ->
      module.executeAsCollection(invocation.getFunctionName(), input)
    );
  }
}
