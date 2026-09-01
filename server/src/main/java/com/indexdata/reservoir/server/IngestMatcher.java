package com.indexdata.reservoir.server;

import com.indexdata.reservoir.module.ModuleExecutable;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;

public class IngestMatcher {
  boolean onlyPayload;
  String poolId;
  ModuleExecutable[] moduleExecutables;
  private Future<Void> closeFuture;

  /** Close executables that are part of this matcher. */
  public synchronized Future<Void> close() {
    if (closeFuture == null) {
      List<Future<Void>> futures = new ArrayList<>();
      for (ModuleExecutable module : moduleExecutables) {
        futures.add(module.close());
      }
      closeFuture = Future.all(futures).mapEmpty();
    }
    return closeFuture;
  }

  /** Close a collection of matchers. */
  static Future<Void> closeAll(List<IngestMatcher> ingestMatchers) {
    List<Future<Void>> futures = new ArrayList<>();
    for (IngestMatcher ingestMatcher : ingestMatchers) {
      futures.add(ingestMatcher.close());
    }
    return Future.all(futures).mapEmpty();
  }
}
