package com.indexdata.reservoir.module.impl;

import com.indexdata.reservoir.module.Module;
import com.indexdata.reservoir.module.ModuleCache;
import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.HashMap;
import java.util.Map;

public class ModuleCacheImpl implements ModuleCache {

  private static class LazyInstance {
    static final ModuleCacheImpl instance = new ModuleCacheImpl();
  }

  public static ModuleCache getInstance() {
    return LazyInstance.instance;
  }

  private Map<String, CacheEntry> entries = new HashMap<>();

  private ModuleCacheImpl() { }

  private class CacheEntry {
    private final Module module;
    private final CodeModuleEntity entity;

    CacheEntry(Module module, CodeModuleEntity entity) {
      this.module = module;
      this.entity = entity;
    }

  }

  private Module createInstance(String type) {
    if (type == null) {
      type = "javascript";
    }
    switch (type) {
      case "jsonpath": return new ModuleJsonPath();
      case "javascript": return new ModuleJavaScript();
      case "": return new ModuleJavaScript();
      default: throw new IllegalArgumentException("Unknown module type '" + type + "'");
    }
  }

  @Override
  public Future<Module> lookup(Vertx vertx, String tenantId, CodeModuleEntity entity) {
    String moduleId = entity.getId();
    if (moduleId == null) {
      return Future.failedFuture("module config must include 'id'");
    }
    String cacheKey = tenantId + ":" + moduleId;
    CacheEntry entry = entries.get(cacheKey);
    if (entry != null) {
      if (entry.entity.equals(entity)) {
        return Future.succeededFuture(entry.module);
      }
      entry.module.terminate();
      entries.remove(cacheKey);
    }
    Module module = createInstance(entity.getType());
    return module.initialize(vertx, tenantId, entity).map(newEntity -> {
      CacheEntry e = new CacheEntry(module, newEntity);
      entries.put(cacheKey, e);
      return module;
    });
  }

  @Override
  public void purge(String tenantId, String moduleId) {
    String cacheKey = tenantId + ":" + moduleId;
    CacheEntry entry = entries.remove(cacheKey);
    if (entry != null) {
      entry.module.terminate();
    }
  }

  @Override
  public void purgeAll() {
    entries.forEach((x,y) -> y.module.terminate());
    entries.clear();
  }

}
