package com.indexdata.reservoir.server.db;

import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lightweight dynamic SQL builder that automatically numbers PostgreSQL {@code $1, $2, ...}
 * placeholders from {@code ?} markers in appended fragments.
 */
public final class SqlBuilder {

  /** Immutable pair of rendered SQL text and bound parameters. */
  public record SqlQuery(String sql, Tuple params) {
    public SqlQuery {
      Objects.requireNonNull(sql, "sql must not be null");
      Objects.requireNonNull(params, "params must not be null");
    }
  }

  private final StringBuilder sql = new StringBuilder();
  private final List<Object> params = new ArrayList<>();
  private boolean hasWhere;

  private SqlBuilder() { }

  public static SqlBuilder of(String initialSql, Object... values) {
    return new SqlBuilder().append(initialSql, values);
  }

  public static SqlBuilder select(String columns) {
    return of("SELECT " + columns);
  }

  public static SqlBuilder selectDistinctOn(String distinctOn, String columns) {
    return of("SELECT DISTINCT ON (" + distinctOn + ") " + columns);
  }

  public SqlBuilder from(String table) {
    sql.append(" FROM ").append(table);
    return this;
  }

  public SqlBuilder leftJoin(String table, String onCondition) {
    sql.append(" LEFT JOIN ").append(table).append(" ON ").append(onCondition);
    return this;
  }

  /** Append a parameterized WHERE (or AND) predicate. */
  public SqlBuilder where(String predicate, Object... values) {
    sql.append(hasWhere ? " AND " : " WHERE ");
    hasWhere = true;
    return append(predicate, values);
  }

  /** Append a pre-formatted raw SQL WHERE (or AND) predicate if non-blank. */
  public SqlBuilder whereRaw(String rawPredicate) {
    if (rawPredicate == null || rawPredicate.isBlank()) {
      return this;
    }
    sql.append(hasWhere ? " AND (" : " WHERE (").append(rawPredicate).append(')');
    hasWhere = true;
    return this;
  }

  public SqlBuilder and(String predicate, Object... values) {
    return where(predicate, values);
  }

  public SqlBuilder andRaw(String rawPredicate) {
    return whereRaw(rawPredicate);
  }

  /** Append an ORDER BY clause if non-blank. */
  public SqlBuilder orderBy(String orderByClause) {
    if (orderByClause != null && !orderByClause.isBlank()) {
      sql.append(" ORDER BY ").append(orderByClause);
    }
    return this;
  }

  public SqlBuilder limit(int limit) {
    return append(" LIMIT ?", limit);
  }

  public SqlBuilder offset(int offset) {
    return append(" OFFSET ?", offset);
  }

  /** Append a SQL fragment, binding each '?' placeholder sequentially. */
  public SqlBuilder append(String fragment, Object... values) {
    int expected = values == null ? 0 : values.length;
    int placeholderCount = 0;
    for (int i = 0; i < fragment.length(); i++) {
      if (fragment.charAt(i) == '?') {
        placeholderCount++;
      }
    }
    if (placeholderCount != expected) {
      throw new IllegalArgumentException(
          "Expected " + expected + " '?' placeholders but found "
              + placeholderCount + " in fragment: " + fragment);
    }
    if (expected == 0) {
      sql.append(fragment);
      return this;
    }
    int valueIndex = 0;
    for (int i = 0; i < fragment.length(); i++) {
      char c = fragment.charAt(i);
      if (c == '?') {
        params.add(values[valueIndex++]);
        sql.append('$').append(params.size());
      } else {
        sql.append(c);
      }
    }
    return this;
  }

  public SqlQuery build() {
    return new SqlQuery(sql.toString(), Tuple.from(params));
  }
}
