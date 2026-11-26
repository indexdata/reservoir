package com.indexdata.reservoir.server.entity;

import io.vertx.core.json.JsonObject;

/**
 * Explicit JSON codec for {@link CodeModuleEntity}.
 */
public final class CodeModuleJson {

  public static final String ID = "id";
  public static final String TYPE = "type";
  public static final String URL = "url";
  @Deprecated(forRemoval = true, since = "1.0")
  public static final String FUNCTION = "function";
  public static final String SCRIPT = "script";

  private CodeModuleJson() { }

  /** Decode a CodeModuleEntity from JSON. */
  public static CodeModuleEntity fromJson(JsonObject source) {
    return new CodeModuleEntity(
        source.getString(ID),
        source.getString(TYPE),
        source.getString(URL),
        source.getString(FUNCTION),
        source.getString(SCRIPT));
  }

  /** Encode a CodeModuleEntity to JSON omitting null fields. */
  public static JsonObject toJson(CodeModuleEntity module) {
    JsonObject json = new JsonObject();
    JsonCodec.putIfPresent(json, ID, module.id());
    JsonCodec.putIfPresent(json, TYPE, module.type());
    JsonCodec.putIfPresent(json, URL, module.url());
    JsonCodec.putIfPresent(json, FUNCTION, module.function());
    JsonCodec.putIfPresent(json, SCRIPT, module.script());
    return json;
  }
}
