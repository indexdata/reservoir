package com.indexdata.reservoir.server.db;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import java.util.Objects;
import java.util.function.Function;
import org.folio.tlib.postgres.TenantPgPool;

/** Tenant-scoped PostgreSQL pool and schema-qualified table registry. */
public record TenantDb(TenantPgPool pool, String tenant, Tables tables) {

  public TenantDb {
    Objects.requireNonNull(pool, "pool must not be null");
    Objects.requireNonNull(tenant, "tenant must not be null");
    Objects.requireNonNull(tables, "tables must not be null");
  }

  /** Create a tenant database handle using the read or write pool for the HTTP method. */
  public static TenantDb forTenant(Vertx vertx, String tenant, HttpMethod method) {
    String key = poolKey(method);
    TenantPgPool pool = TenantPgPool.pool(vertx, tenant, key);
    return new TenantDb(pool, tenant, new Tables(pool.getSchema()));
  }

  public static TenantDb forRead(Vertx vertx, String tenant) {
    return forTenant(vertx, tenant, HttpMethod.GET);
  }

  public static TenantDb forWrite(Vertx vertx, String tenant) {
    return forTenant(vertx, tenant, HttpMethod.POST);
  }

  public static String poolKey(HttpMethod method) {
    return HttpMethod.GET.equals(method) ? "read" : "write";
  }

  public SqlClient client() {
    return pool;
  }

  public <T> Future<T> withConnection(Function<SqlConnection, Future<T>> function) {
    return pool.withConnection(function);
  }

  public <T> Future<T> withTransaction(Function<SqlConnection, Future<T>> function) {
    return pool.withTransaction(function);
  }
}
