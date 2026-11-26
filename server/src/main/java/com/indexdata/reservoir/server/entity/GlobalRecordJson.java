package com.indexdata.reservoir.server.entity;

import io.vertx.core.json.JsonObject;

/**
 * Explicit JSON codec for {@link GlobalRecord}.
 */
public final class GlobalRecordJson {

  public static final String GLOBAL_ID = "globalId";
  public static final String LOCAL_ID = "localId";
  public static final String SOURCE_ID = "sourceId";
  public static final String SOURCE_VERSION = "sourceVersion";
  public static final String PAYLOAD = "payload";

  private GlobalRecordJson() { }

  /** Encode a GlobalRecord to JSON. */
  public static JsonObject toJson(GlobalRecord record) {
    JsonObject json = new JsonObject();
    if (record.id() != null) {
      json.put(GLOBAL_ID, record.id().toString());
    }
    json.put(LOCAL_ID, record.localId())
        .put(SOURCE_ID, record.sourceId())
        .put(SOURCE_VERSION, record.sourceVersion());
    JsonCodec.putIfPresent(json, PAYLOAD, record.payload());
    return json;
  }
}
