package com.indexdata.reservoir.module;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.Collection;

public class ModuleExecutable {
  private final Module module;
  private final ModuleInvocation invocation;

  /**
   * Create module executable by passing it a module and am invocation.
   * @param module  module to be executed
   * @param invocation execution invocation
   */
  public ModuleExecutable(Module module, ModuleInvocation invocation) {
    this.invocation = invocation;
    this.module = module;
  }


  public Future<JsonObject> execute(JsonObject input) {
    return module.execute(invocation.getFunctionName(), input);

  }

  public Future<Collection<String>> executeAsCollection(JsonObject input) {
    return module.executeAsCollection(invocation.getFunctionName(), input);
  }

}
