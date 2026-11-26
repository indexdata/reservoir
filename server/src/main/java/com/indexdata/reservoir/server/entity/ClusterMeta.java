package com.indexdata.reservoir.server.entity;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain record for a {@code cluster_meta} row.
 */
public record ClusterMeta(
    UUID clusterId,
    String poolId,
    LocalDateTime datestamp
) {
  public ClusterMeta {
    Objects.requireNonNull(clusterId, "clusterId must not be null");
  }
}
