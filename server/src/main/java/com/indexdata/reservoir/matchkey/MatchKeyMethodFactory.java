package org.folio.reservoir.matchkey;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.Map;
import org.folio.reservoir.matchkey.impl.MatchKeyJavaScript;
import org.folio.reservoir.matchkey.impl.MatchKeyJsonPath;

/**
 * Don't use this.
 * @deprecated for removal
 */
@Deprecated(forRemoval = true, since = "1.0")
public final class MatchKeyMethodFactory {

  private MatchKeyMethodFactory() {
    throw new UnsupportedOperationException("MatchKeyMethodFactory");
  }

  private static Map<String, MatchKeyMethodEntry> instances = new HashMap<>();

  /**
   * Get MatchKeyMethod instance from method.
   * @param method method name
   * @return method or NULL if not found
   */
  public static MatchKeyMethod get(String method) {
    if ("jsonpath".equals(method)) {
      return new MatchKeyJsonPath();
    } else if ("javascript".equals(method)) {
      return new MatchKeyJavaScript();
    }
    return null;
  }

  static Future<MatchKeyMethod> get(Vertx vertx, String tenant, String id,
      String method, JsonObject configuration) {
    String primaryKey = tenant + "-" + id;
    JsonObject conf = new JsonObject()
        .put("method", method)
        .put("params", configuration);

    MatchKeyMethodEntry entry = instances.get(primaryKey);
    if (entry != null) {
      if (entry.conf.equals(conf)) {
        return Future.succeededFuture(entry.method);
      }
      entry.method.close();
      instances.remove(primaryKey);
    }
    MatchKeyMethod m = get(method);
    if (m == null) {
      return Future.failedFuture("Unknown match key method " + method);
    }
    try {
      return m.configure(vertx, configuration).map(x -> {
        MatchKeyMethodEntry newEntry = new MatchKeyMethodEntry();
        newEntry.conf = conf;
        newEntry.method = m;
        instances.put(primaryKey, newEntry);
        return m;
      });
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  static void clearCache() {
    instances.forEach((x,y) -> y.method.close());
    instances.clear();
  }

  static int getCacheSize() {
    return instances.size();
  }
}
