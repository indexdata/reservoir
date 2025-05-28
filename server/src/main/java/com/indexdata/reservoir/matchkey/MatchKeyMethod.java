package org.folio.reservoir.matchkey;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.Collection;

/**
 * Don't use this.
 * @deprecated for removal
 */
@Deprecated(forRemoval = true, since = "1.0")
public interface MatchKeyMethod {

  /**
   * Get MatchKeyMethod instance with configuration.
   * @param vertx Vert.x handle
   * @param tenant tenant
   * @param id matchkey id
   * @param method method name
   * @param configuration configuration
   * @return Async result MatchKeyMethod
   */
  static Future<MatchKeyMethod> get(Vertx vertx, String tenant, String id,
      String method, JsonObject configuration) {
    return MatchKeyMethodFactory.get(vertx, tenant, id, method, configuration);
  }

  Future<Void> configure(Vertx vertx, JsonObject configuration);

  /**
   * Generate match keys.
   * @param payload payload with marc and inventory XSLT result
   * @param keys resulting keys (unmodified if no keys were generated).
   */
  void getKeys(JsonObject payload, Collection<String> keys);

  /**
   * Close resources for method.
   */
  default void close() { }
}
