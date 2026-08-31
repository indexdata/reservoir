package com.indexdata.reservoir.module;

import com.indexdata.reservoir.module.impl.ModuleCacheImpl;
import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import io.vertx.core.Future;

public interface ModuleCache {

  static ModuleCache getInstance() {
    return ModuleCacheImpl.getInstance();
  }

  public Future<Module> lookup(String tenant, CodeModuleEntity entity);

  /**
   * Create a new, uncached, initialized module instance for the given entity.
   * Useful for building a pool of parallel workers, each with its own module instance.
   * @param entity module configuration
   * @return a freshly initialized module
   */
  Module newInstance(CodeModuleEntity entity);

  void purge(String tenant, String id);

  void purgeAll();

}
