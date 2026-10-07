package com.indexdata.reservoir.server.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Initialization work attempted, including work subsequently rolled back. */
public final class InitializationMetrics {
  private final MeterRegistry registry;
  private final Map<String, Timer> timers = new HashMap<>();
  private final Map<String, Counter> rows = new HashMap<>();
  private final Counter recordsSucceeded;
  private final Counter recordsFailed;
  private final Counter clusters;
  private final DistributionSummary keys;

  /** Register initialization metrics in the enabled backend. */
  public InitializationMetrics(MeterRegistry registry) {
    this.registry = registry;
    recordsSucceeded = records("success");
    recordsFailed = records("failure");
    clusters = Counter.builder("reservoir_initialization_clusters_found_total")
        .description("Clusters found during attempted initialization work")
        .register(registry);
    keys = DistributionSummary.builder("reservoir_initialization_keys_per_record")
        .description("Keys returned by the matcher per attempted record")
        .serviceLevelObjectives(0.5, 1, 2, 5, 10, 20, 50, 100, 250, 500, 1000)
        .register(registry);
  }

  private Counter records(String outcome) {
    return Counter.builder("reservoir_initialization_record_attempts_total")
        .description("Record attempts; success does not imply transaction commit")
        .tag("outcome", outcome).register(registry);
  }

  private String stage(String stage) {
    return stage.startsWith("lookup.keys=") ? "lookup" : stage;
  }

  /** Record stage latency without introducing key-count labels. */
  public void elapsed(String stage, long nanos, boolean success) {
    String normalized = stage(stage);
    String outcome = success ? "success" : "failure";
    timers.computeIfAbsent(normalized + ":" + outcome, ignored ->
        Timer.builder("reservoir_initialization_stage_duration_seconds")
            .description("Initialization stage duration, including attempted work")
            .tags("stage", normalized, "outcome", outcome)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofNanos(1000))
            .maximumExpectedValue(Duration.ofMinutes(5))
            .register(registry)).record(nanos, TimeUnit.NANOSECONDS);
  }

  /** Count rows from attempted statements, regardless of later transaction outcome. */
  public void rows(String stage, int count) {
    rows.computeIfAbsent(stage(stage), normalized ->
        Counter.builder("reservoir_initialization_rows_total")
            .description("Rows returned or affected by attempted statements, before commit")
            .tag("stage", normalized).register(registry)).increment(count);
  }

  public void keys(int count) {
    keys.record(count);
  }

  public void clusters(int count) {
    clusters.increment(count);
  }

  public void recordDone(boolean success) {
    (success ? recordsSucceeded : recordsFailed).increment();
  }
}
