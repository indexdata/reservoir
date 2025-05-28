package org.folio.reservoir.matchkey.impl;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.ReadContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.Collection;
import java.util.List;
import org.folio.reservoir.matchkey.MatchKeyMethod;

/**
 * Don't use this.
 * @deprecated for removal
 */
@Deprecated(forRemoval = true, since = "1.0")
public class MatchKeyJsonPath implements MatchKeyMethod {

  JsonPath jsonPath;

  @Override
  public Future<Void> configure(Vertx vertx, JsonObject configuration) {
    String expr = configuration.getString("expr");
    if (expr == null) {
      return Future.failedFuture("jsonpath: expr must be given");
    }
    jsonPath = JsonPath.compile(expr);
    return Future.succeededFuture();
  }

  @Override
  public void getKeys(JsonObject payload, Collection<String> keys) {
    ReadContext ctx = JsonPath.parse(payload.encode());
    try {
      Object o = ctx.read(jsonPath);
      if (o instanceof String string) {
        keys.add(string);
      } else if (o instanceof List<?> list) {
        for (Object m : list) {
          if (!(m instanceof String)) {
            return;
          }
        }
        keys.addAll((List<String>) o);
      }
    } catch (PathNotFoundException e) {
      // ignored.. no keys added
    }
  }

}
