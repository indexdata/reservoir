package com.indexdata.reservoir.server.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable domain representation of an asynchronous pool initialization job. */
public record PoolInitJob(
    UUID id,
    String poolId,
    PoolInitJobStatus status,
    UUID claimToken,
    Instant leaseUntil,
    UUID checkpoint,
    long totalRecords,
    Instant startedAt,
    Instant completedAt,
    String error
) {
  public PoolInitJob {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(poolId, "poolId must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(startedAt, "startedAt must not be null");
  }
}
