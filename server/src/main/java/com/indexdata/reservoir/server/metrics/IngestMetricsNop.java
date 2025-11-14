package com.indexdata.reservoir.server.metrics;

import com.indexdata.reservoir.util.SourceId;

public class IngestMetricsNop implements IngestMetrics {
  @Override
  public IngestMetrics withSource(SourceId sourceId) {
    return this;
  }

  public void incrementRecordsIgnored() {
  }

  public void incrementRecordsInserted() {
  }

  public void incrementRecordsDeleted() {
  }

  public void incrementRecordsUpdated() {
  }

}
