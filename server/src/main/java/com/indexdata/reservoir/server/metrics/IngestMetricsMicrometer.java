package com.indexdata.reservoir.server.metrics;

import com.indexdata.reservoir.util.SourceId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vertx.micrometer.backends.BackendRegistries;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class IngestMetricsMicrometer implements IngestMetrics {
  static MeterRegistry registry = BackendRegistries.getDefaultNow();

  static ConcurrentHashMap<String, Counter> recordsIgnoredMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Counter> recordsInsertedMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Counter> recordsDeletedMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Counter> recordsUpdatedMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Timer> timerMatcherMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Timer> timerStoringMap = new ConcurrentHashMap<>();
  static ConcurrentHashMap<String, Timer> timerParsingMap = new ConcurrentHashMap<>();

  Counter recordsIgnoredTotal;
  Counter recordsInsertedTotal;
  Counter recordsDeletedTotal;
  Counter recordsUpdatedTotal;
  Timer timerMatcher;
  Timer timerStoring;
  Timer timerParsing;

  /** create counter for source and with result.
   * This is public, so we can use it for tests.
   * @param sourceId source_id tag for counter
   * @param result result tag for counter
   * @return created counter
   */
  public static Counter createCounter(SourceId sourceId, String result) {
    return Counter.builder("reservoir_records_ingested_total")
      .description("Total number of reservoir records ingested")
      .tag("source_id", sourceId.toString())
      .tag("result", result)
      .register(registry);
  }

  private Counter getCounter(Map<String, Counter> counterMap, SourceId sourceId, String result) {
    return counterMap.computeIfAbsent(sourceId.toString() + "_" + result,
        id -> createCounter(sourceId, result));
  }

  /** create timer for source and with phase.
   * This is public, so we can use it for tests.
   * @param sourceId source_id tag for timer
   * @param phase phase tag for timer
   * @return created timer
  */
  public static Timer createTimer(SourceId sourceId, String phase) {
    long minExpectedNs = 10_000_000; // 10 ms
    long maxExpectedNS = 1_000_000_000; // 1 second
    if (phase.equals("matcher")) {
      minExpectedNs = 10_000; // 10 us
      maxExpectedNS = 500_000_000; // 500 ms
    }
    return Timer.builder("reservoir_ingestion_duration_seconds")
      .description("Time spent ingesting reservoir records")
      .publishPercentileHistogram()
      .minimumExpectedValue(Duration.ofNanos(minExpectedNs))
      .maximumExpectedValue(Duration.ofNanos(maxExpectedNS))
      .tag("source_id", sourceId.toString())
      .tag("phase", phase)
      .register(registry);
  }

  private Timer getTimer(Map<String, Timer> timerMap, SourceId sourceId, String phase) {
    return timerMap.computeIfAbsent(sourceId.toString() + "_" + phase,
        id -> createTimer(sourceId, phase));
  }

  @Override
  public IngestMetrics withSource(SourceId sourceId) {
    recordsIgnoredTotal = getCounter(recordsIgnoredMap, sourceId, "ignored");
    recordsInsertedTotal = getCounter(recordsInsertedMap, sourceId, "inserted");
    recordsDeletedTotal = getCounter(recordsDeletedMap, sourceId, "deleted");
    recordsUpdatedTotal = getCounter(recordsUpdatedMap, sourceId, "updated");
    timerMatcher = getTimer(timerMatcherMap, sourceId, "matcher");
    timerStoring = getTimer(timerStoringMap, sourceId, "storing");
    timerParsing = getTimer(timerParsingMap, sourceId, "parsing");
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

  @Override
  public void recordStoring(long amount, TimeUnit unit) {
    timerStoring.record(amount, unit);
  }

  @Override
  public void recordParsing(long amount, TimeUnit unit) {
    timerParsing.record(amount, unit);
  }

}
