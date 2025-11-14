package com.indexdata.reservoir.server.metrics;

import com.indexdata.reservoir.util.SourceId;
import io.vertx.micrometer.backends.BackendRegistries;

public interface IngestMetrics {
  IngestMetrics withSource(SourceId sourceId);

  void incrementRecordsIgnored();

  void incrementRecordsInserted();

  void incrementRecordsDeleted();

  void incrementRecordsUpdated();

  /** Create IngestMetrics instance and use default backend if available. */
  static IngestMetrics create() {
    if (BackendRegistries.getDefaultNow() != null) {
      return new IngestMetricsMicrometer();
    }
    return new IngestMetricsNop();
  }
}
