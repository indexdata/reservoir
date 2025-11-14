package com.indexdata.reservoir.server.metrics;

import com.indexdata.reservoir.util.SourceId;
import io.micrometer.core.instrument.Counter;
import io.vertx.micrometer.backends.BackendRegistries;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class IngestMetricsMicrometer implements IngestMetrics {
  static ConcurrentHashMap<String, Counter> recordsIgnoredMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Counter> recordsInsertedMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Counter> recordsDeletedMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Counter> recordsUpdatedMap = new ConcurrentHashMap<>();

  Counter recordsIgnoredTotal;
  Counter recordsInsertedTotal;
  Counter recordsDeletedTotal;
  Counter recordsUpdatedTotal;

  private Counter updateCounter(Map<String, Counter> counterMap, SourceId sourceId, String result) {
    return counterMap.computeIfAbsent(sourceId.toString() + "_" + result,
        id -> Counter.builder("reservoir_records_ingested_total")
          .description("Total number of reservoir records ingested")
          .tag("source_id", sourceId.toString())
          .tag("result", result)
          .register(BackendRegistries.getDefaultNow()));
  }

  @Override
  public IngestMetrics withSource(SourceId sourceId) {
    recordsIgnoredTotal = updateCounter(recordsIgnoredMap, sourceId, "ignored");
    recordsInsertedTotal = updateCounter(recordsInsertedMap, sourceId, "inserted");
    recordsDeletedTotal = updateCounter(recordsDeletedMap, sourceId, "deleted");
    recordsUpdatedTotal = updateCounter(recordsUpdatedMap, sourceId, "updated");
    return this;
  }

  @Override
  public void incrementRecordsIgnored() {
    recordsIgnoredTotal.increment();
  }

  @Override
  public void incrementRecordsInserted() {
    recordsInsertedTotal.increment();
  }

  @Override
  public void incrementRecordsDeleted() {
    recordsDeletedTotal.increment();
  }

  @Override
  public void incrementRecordsUpdated() {
    recordsUpdatedTotal.increment();
  }
}
