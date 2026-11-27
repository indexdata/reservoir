package com.indexdata.reservoir.server.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable domain record for pool clustering statistics with deterministic map key order. */
public record PoolStats(
    int recordsTotal,
    int clustersTotal,
    Map<Integer, Integer> matchValuesPerCluster,
    Map<Integer, Integer> recordsPerCluster,
    Map<Integer, List<UUID>> recordsPerClusterSample
) {
  public PoolStats {
    matchValuesPerCluster = Collections.unmodifiableMap(new LinkedHashMap<>(matchValuesPerCluster));
    recordsPerCluster = Collections.unmodifiableMap(new LinkedHashMap<>(recordsPerCluster));
    recordsPerClusterSample = Collections.unmodifiableMap(
        new LinkedHashMap<>(recordsPerClusterSample));
  }
}
