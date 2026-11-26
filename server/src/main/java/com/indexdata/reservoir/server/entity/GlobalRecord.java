package com.indexdata.reservoir.server.entity;

import io.vertx.core.json.JsonObject;
import java.util.Objects;
import java.util.UUID;

/** Immutable domain representation of a stored global record. */
public record GlobalRecord(
    UUID id,
    String localId,
    String sourceId,
    int sourceVersion,
    JsonObject payload
) {
  public GlobalRecord {
    Objects.requireNonNull(localId, "localId must not be null");
    Objects.requireNonNull(sourceId, "sourceId must not be null");
  }
}
