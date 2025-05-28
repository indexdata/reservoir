package com.indexdata.reservoir.module;

import com.indexdata.reservoir.module.impl.ModuleCacheImpl;
import com.indexdata.reservoir.server.entity.CodeModuleEntity;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

public interface ModuleCache {

  static ModuleCache getInstance() {
    return ModuleCacheImpl.getInstance();
  }

  public Future<Module> lookup(Vertx vertx, String tenant, CodeModuleEntity entity);

  void purge(String tenant, String id);

  void purgeAll();

}
