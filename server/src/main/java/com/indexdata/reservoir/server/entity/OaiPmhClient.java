package com.indexdata.reservoir.server.entity;

import io.vertx.core.json.JsonObject;
import java.util.Objects;
import java.util.UUID;

/** Immutable domain record representing an OAI-PMH client row and its harvest state. */
public record OaiPmhClient(
    String id,
    JsonObject config,
    OaiPmhStatus status,
    boolean stop,
    UUID owner,
    boolean leased
) {

  public OaiPmhClient {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(status, "status must not be null");
  }

  /**
   * Returns a defensive copy of the client configuration with its {@code id} populated.
   */
  public JsonObject configWithId() {
    JsonObject copy = config != null ? config.copy() : new JsonObject();
    copy.put("id", id);
    return copy;
  }
}
