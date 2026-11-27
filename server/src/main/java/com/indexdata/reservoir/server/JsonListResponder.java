package com.indexdata.reservoir.server;

import com.indexdata.reservoir.server.db.RowStreamer;
import com.indexdata.reservoir.server.db.SqlBuilder;
import com.indexdata.reservoir.server.db.TenantDb;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Row;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * HTTP-layer responder for paginated JSON list endpoints
 * ({@code {"items":[...],"resultInfo":...}}).
 * Keeps {@link RoutingContext} completely out of DB repositories.
 */
final class JsonListResponder {

  private static final Logger log = LogManager.getLogger(JsonListResponder.class);

  record PageParams(int offset, int limit, boolean exactCount) {
    static PageParams from(RoutingContext ctx) {
      int offset = Integer.parseInt(Util.getQueryParameter(ctx, "offset", "0"));
      int limit = Integer.parseInt(Util.getQueryParameter(ctx, "limit", "10"));
      boolean exact = "exact".equals(Util.getQueryParameter(ctx, "count"));
      return new PageParams(offset, limit, exact);
    }
  }

  private JsonListResponder() { }

  /**
   * Stream rows from a single DB cursor transaction directly to the HTTP response as a JSON list.
   */
  static Future<Void> streamTable(RoutingContext ctx, TenantDb db, String columns, String table,
      String sqlWhere, String sqlOrderBy, String property, int fetchSize,
      Function<Row, JsonObject> mapper) {
    PageParams page = PageParams.from(ctx);
    SqlBuilder itemsBuilder = SqlBuilder.select(columns)
        .from(table)
        .whereRaw(sqlWhere)
        .orderBy(sqlOrderBy)
        .limit(page.limit())
        .offset(page.offset());
    SqlBuilder.SqlQuery itemsQuery = itemsBuilder.build();
    SqlBuilder.SqlQuery countQuery = page.exactCount()
        ? SqlBuilder.select("COUNT(*)").from(table).whereRaw(sqlWhere).build()
        : null;
    log.info("query={}", itemsQuery.sql());
    return db.withConnection(conn -> {
      HttpServerResponse response = ctx.response();
      AtomicBoolean headerWritten = new AtomicBoolean();
      AtomicBoolean first = new AtomicBoolean(true);
      Runnable ensureHeader = () -> {
        if (headerWritten.compareAndSet(false, true)) {
          response.setChunked(true);
          response.putHeader("Content-Type", "application/json");
          response.write("{ \"" + property + "\" : [");
        }
      };
      return RowStreamer.<Integer>streamAsync(conn, itemsQuery, fetchSize,
          row -> {
            ensureHeader.run();
            JsonObject encoded = mapper.apply(row);
            if (!first.getAndSet(false)) {
              response.write(",");
            }
            response.write(encoded.encode());
            return Future.succeededFuture();
          },
          () -> {
            ensureHeader.run();
            if (countQuery == null) {
              return Future.succeededFuture(null);
            }
            return conn.preparedQuery(countQuery.sql())
                .execute(countQuery.params())
                .map(rows -> rows.iterator().next().getInteger(0));
          })
          .onSuccess(total -> writeFooter(response, total, null))
          .onFailure(err -> {
            if (headerWritten.get()) {
              writeFooter(response, null, err.getMessage());
            }
          })
          .recover(err -> headerWritten.get() ? Future.succeededFuture() : Future.failedFuture(err))
          .mapEmpty();
    });
  }

  /**
   * Load and write items for a list of IDs in bounded DB query chunks.
   */
  static <T> Future<Void> streamByChunks(RoutingContext ctx, String property, List<T> ids,
      int chunkSize, Function<List<T>, Future<List<JsonObject>>> chunkLoader,
      Supplier<Future<Integer>> totalSupplier) {
    HttpServerResponse response = ctx.response();
    response.setChunked(true);
    response.putHeader("Content-Type", "application/json");
    response.write("{ \"" + property + "\" : [");
    AtomicBoolean first = new AtomicBoolean(true);
    Future<Void> chain = Future.succeededFuture();
    for (int start = 0; start < ids.size(); start += chunkSize) {
      List<T> chunk = ids.subList(start, Math.min(start + chunkSize, ids.size()));
      chain = chain.compose(_ -> chunkLoader.apply(chunk)
          .onSuccess(items -> {
            for (JsonObject item : items) {
              if (!first.getAndSet(false)) {
                response.write(",");
              }
              response.write(item.encode());
            }
          })
          .mapEmpty());
    }
    return chain
        .compose(_ -> totalSupplier != null ? totalSupplier.get() : Future.succeededFuture(null))
        .onSuccess(total -> writeFooter(response, total, null))
        .onFailure(err -> writeFooter(response, null, err.getMessage()))
        .recover(_ -> Future.succeededFuture())
        .mapEmpty();
  }

  private static void writeFooter(HttpServerResponse response, Integer totalRecords,
      String diagnostic) {
    JsonObject resultInfo = new JsonObject();
    if (totalRecords != null) {
      resultInfo.put("totalRecords", totalRecords);
    }
    JsonArray diagnostics = new JsonArray();
    if (diagnostic != null) {
      diagnostics.add(new JsonObject().put("message", diagnostic));
    }
    resultInfo.put("diagnostics", diagnostics);
    resultInfo.put("facets", new JsonArray());
    response.write("], \"resultInfo\": " + resultInfo.encode() + "}");
    response.end();
  }
}
