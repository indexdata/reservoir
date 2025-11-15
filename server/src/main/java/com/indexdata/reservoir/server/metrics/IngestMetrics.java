package com.indexdata.reservoir.server.metrics;

import com.indexdata.reservoir.util.SourceId;
import io.vertx.micrometer.backends.BackendRegistries;
import java.util.concurrent.TimeUnit;

public interface IngestMetrics {
  IngestMetrics withSource(SourceId sourceId);

  void incrementRecordsIgnored();

  void incrementRecordsInserted();

  void incrementRecordsDeleted();

  void incrementRecordsUpdated();

  void recordMatcher(long amount, TimeUnit unit);

  void recordStoring(long amount, TimeUnit unit);

  void recordParsing(long amount, TimeUnit unit);

  /** Create IngestMetrics instance and use default backend if available. */
  static IngestMetrics create() {
    if (BackendRegistries.getDefaultNow() != null) {
      return new IngestMetricsMicrometer();
    }
    return new IngestMetricsNop();
  }
}
