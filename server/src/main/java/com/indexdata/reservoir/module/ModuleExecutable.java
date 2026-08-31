package com.indexdata.reservoir.module;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ModuleExecutable {
  private final ModuleInvocation invocation;
  private final List<Worker> workers;
  private final boolean ownsModules;

  /**
   * A single worker: its own module instance plus a dedicated single-thread executor.
   * A Graal JS module keeps a Context that is not safe to call from multiple threads
   * at once, so each worker gets its own module instance and its own thread.
   */
  private static final class Worker {
    final Module module;
    final WorkerExecutor executor;
    final AtomicInteger pending = new AtomicInteger();

    Worker(Module module, WorkerExecutor executor) {
      this.module = module;
      this.executor = executor;
    }
  }

  /**
   * Create module executable with a single worker for an already constructed module.
   * @param module  module to be executed
   * @param invocation execution invocation
   * @param vertx Vertx instance to use for executing the module
   */
  public ModuleExecutable(Module module, ModuleInvocation invocation, Vertx vertx) {
    this(List.of(module), invocation, vertx, false);
  }

  /**
   * Create module executable backed by a pool of workers so that several invocations
   * can run in parallel, each with its own Graal JS module instance.
   * @param moduleSupplier supplies a fresh, initialized module instance for each worker
   * @param invocation execution invocation
   * @param vertx Vertx instance to use for executing the module
   * @param numWorkers number of parallel workers to create
   */
  public ModuleExecutable(Supplier<Module> moduleSupplier, ModuleInvocation invocation,
      Vertx vertx, int numWorkers) {
    this(buildModules(moduleSupplier, numWorkers), invocation, vertx, true);
  }

  private static List<Module> buildModules(Supplier<Module> moduleSupplier, int numWorkers) {
    if (numWorkers < 1) {
      throw new IllegalArgumentException("numWorkers must be at least 1");
    }
    return IntStream.range(0, numWorkers)
        .mapToObj(i -> moduleSupplier.get())
        .collect(Collectors.toList());
  }

  private ModuleExecutable(List<Module> modules, ModuleInvocation invocation, Vertx vertx,
      boolean ownsModules) {
    this.invocation = invocation;
    this.ownsModules = ownsModules;
    this.workers = modules.stream()
        .map(module -> new Worker(module,
            vertx.createSharedWorkerExecutor("module-executable-" + UUID.randomUUID(), 1)))
        .collect(Collectors.toList());
  }

  private Worker selectWorker() {
    Worker chosen = workers.get(0);
    for (Worker worker : workers) {
      if (worker.pending.get() < chosen.pending.get()) {
        chosen = worker;
      }
    }
    return chosen;
  }

  private <T> Future<T> submit(Function<Module, T> task) {
    Worker worker = selectWorker();
    worker.pending.incrementAndGet();
    return worker.executor.<T>executeBlocking(() -> task.apply(worker.module), false)
        .eventually(() -> {
          worker.pending.decrementAndGet();
          return Future.succeededFuture();
        });
  }

  /**
   * Execute this module with the given input, returning a future that completes with the result.
   * @param input the input JSON object
   * @return a future that completes with the output JSON object
   */
  public Future<JsonObject> execute(JsonObject input) {
    return submit(module -> module.execute(invocation.getFunctionName(), input));
  }

  public Collection<String> executeAsCollectionSync(JsonObject input) {
    return workers.get(0).module.executeAsCollection(invocation.getFunctionName(), input);
  }

  /**
   * Execute this module as a collection, dispatching the work to whichever worker
   * currently has the fewest pending tasks, and returning a future that completes
   * with the result.
   * @param input the input JSON object
   * @return a future that completes with the collection of strings
   */
  public Future<Collection<String>> executeAsCollection(JsonObject input) {
    return submit(module -> module.executeAsCollection(invocation.getFunctionName(), input));
  }

  /**
   * Close the worker executors and, for a multi-worker executable, terminate the module
   * instances it created. Modules passed in via the single-module constructor are left
   * running since their lifecycle is owned by the caller (e.g. the module cache).
   * @return future that completes when all workers are closed
   */
  public Future<Void> close() {
    List<Future<Void>> futures = new ArrayList<>();
    for (Worker worker : workers) {
      if (ownsModules) {
        worker.module.terminate();
      }
      futures.add(worker.executor.close());
    }
    return Future.all(futures).mapEmpty();
  }
}
