package com.indexdata.reservoir.server.metrics;

import com.indexdata.reservoir.util.SourceId;
import io.micrometer.core.instrument.Counter;
import io.vertx.micrometer.backends.BackendRegistries;
import java.util.HashMap;
import java.util.Map;

class IngestMetricsMicrometer implements IngestMetrics {
  static Map<String, Counter> recordsProcessedMap = new HashMap<>();
  static Map<String, Counter> recordsIgnoredMap = new HashMap<>();
  static Map<String, Counter> recordsInsertedMap = new HashMap<>();
  static Map<String, Counter> recordsDeletedMap = new HashMap<>();
  static Map<String, Counter> recordsUpdatedMap = new HashMap<>();

  Counter recordsProcessedTotal;
  Counter recordsIgnoredTotal;
  Counter recordsInsertedTotal;
  Counter recordsDeletedTotal;
  Counter recordsUpdatedTotal;

  private Counter updateCounter(Map<String, Counter> counterMap, String metricName,
      String description, SourceId sourceId) {
    return counterMap.computeIfAbsent(sourceId.toString(),
        id -> Counter.builder(metricName)
          .description(description)
          .tag("source_id", id)
          .register(BackendRegistries.getDefaultNow()));
  }

  @Override
  public IngestMetrics withSource(SourceId sourceId) {
    recordsProcessedTotal = updateCounter(recordsProcessedMap, "reservoir_records_processed_total",
        "Total number of reservoir records processed", sourceId);
    recordsIgnoredTotal = updateCounter(recordsIgnoredMap, "reservoir_records_ignored_total",
        "Total number of reservoir records ignored", sourceId);
    recordsInsertedTotal = updateCounter(recordsInsertedMap, "reservoir_records_inserted_total",
        "Total number of reservoir records inserted", sourceId);
    recordsDeletedTotal = updateCounter(recordsDeletedMap, "reservoir_records_deleted_total",
        "Total number of reservoir records deleted", sourceId);
    recordsUpdatedTotal = updateCounter(recordsUpdatedMap, "reservoir_records_updated_total",
        "Total number of reservoir records updated", sourceId);
    return this;
  }

  @Override
  public void incrementRecordsIgnored() {
    recordsProcessedTotal.increment();
    recordsIgnoredTotal.increment();
  }

  @Override
  public void incrementRecordsInserted() {
    recordsProcessedTotal.increment();
    recordsInsertedTotal.increment();
  }

  @Override
  public void incrementRecordsDeleted() {
    recordsProcessedTotal.increment();
    recordsDeletedTotal.increment();
  }

  @Override
  public void incrementRecordsUpdated() {
    recordsProcessedTotal.increment();
    recordsUpdatedTotal.increment();
  }
}
