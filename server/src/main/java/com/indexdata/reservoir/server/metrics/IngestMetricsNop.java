package com.indexdata.reservoir.server.metrics;

import com.indexdata.reservoir.util.SourceId;
import java.util.concurrent.TimeUnit;

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

  public void recordMatcher(long amount, TimeUnit unit) {
  }

  public void recordStoring(long amount, TimeUnit unit) {
  }

  public void recordParsing(long amount, TimeUnit unit) {
  }

}
