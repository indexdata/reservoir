package org.folio.reservoir.module;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.folio.reservoir.module.impl.ModuleCacheImpl;
import org.folio.reservoir.server.entity.CodeModuleEntity;

public interface ModuleCache {

  static ModuleCache getInstance() {
    return ModuleCacheImpl.getInstance();
  }

  public Future<Module> lookup(Vertx vertx, String tenant, CodeModuleEntity entity);

  void purge(String tenant, String id);

  void purgeAll();

}
