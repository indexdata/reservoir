package com.indexdata.reservoir.server.metrics;

import com.indexdata.reservoir.util.SourceId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.vertx.micrometer.backends.BackendRegistries;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

class IngestMetricsMicrometer implements IngestMetrics {
  static ConcurrentHashMap<String, Counter> recordsIgnoredMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Counter> recordsInsertedMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Counter> recordsDeletedMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Counter> recordsUpdatedMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Timer> timerMatcherMap = new ConcurrentHashMap<>();

  Counter recordsIgnoredTotal;
  Counter recordsInsertedTotal;
  Counter recordsDeletedTotal;
  Counter recordsUpdatedTotal;
  Timer timerMatcher;

  private Counter updateCounter(Map<String, Counter> counterMap, SourceId sourceId, String result) {
    return counterMap.computeIfAbsent(sourceId.toString() + "_" + result,
        id -> Counter.builder("reservoir_records_ingested_total")
          .description("Total number of reservoir records ingested")
          .tag("source_id", sourceId.toString())
          .tag("result", result)
          .register(BackendRegistries.getDefaultNow()));
  }

  private Timer updateTimer(Map<String, Timer> timerMap, SourceId sourceId, String phase) {
    return timerMap.computeIfAbsent(sourceId.toString() + "_" + phase,
        id -> Timer.builder("reservoir_ingestion_duration_seconds")
          .description("Time spent ingesting reservoir records")
          .publishPercentileHistogram()
          .minimumExpectedValue(Duration.ofNanos(1000))
          .maximumExpectedValue(Duration.ofMillis(200))
          .tag("source_id", sourceId.toString())
          .tag("phase", phase)
          .register(BackendRegistries.getDefaultNow()));
  }

  @Override
  public IngestMetrics withSource(SourceId sourceId) {
    recordsIgnoredTotal = updateCounter(recordsIgnoredMap, sourceId, "ignored");
    recordsInsertedTotal = updateCounter(recordsInsertedMap, sourceId, "inserted");
    recordsDeletedTotal = updateCounter(recordsDeletedMap, sourceId, "deleted");
    recordsUpdatedTotal = updateCounter(recordsUpdatedMap, sourceId, "updated");
    timerMatcher = updateTimer(timerMatcherMap, sourceId, "matcher");
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

  @Override
  public void recordMatcher(long amount, TimeUnit unit) {
    timerMatcher.record(amount, unit);
  }
}
