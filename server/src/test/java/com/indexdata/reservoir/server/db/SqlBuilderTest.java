package com.indexdata.reservoir.server.db;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlBuilderTest {

  @Test
  void autoNumbersPlaceholdersAcrossClauses() {
    UUID clusterId = UUID.randomUUID();
    SqlBuilder.SqlQuery query = SqlBuilder.select("cluster_id, datestamp")
        .from("s.cluster_meta")
        .where("match_key_config_id = ?", "isbn")
        .and("(datestamp = ? AND cluster_id >= ? OR datestamp > ?)", "t1", clusterId, "t1")
        .and("datestamp < ?", "t2")
        .orderBy("datestamp, cluster_id")
        .limit(20)
        .offset(40)
        .build();

    assertThat(query.sql(), is(
        "SELECT cluster_id, datestamp FROM s.cluster_meta"
            + " WHERE match_key_config_id = $1"
            + " AND (datestamp = $2 AND cluster_id >= $3 OR datestamp > $4)"
            + " AND datestamp < $5"
            + " ORDER BY datestamp, cluster_id"
            + " LIMIT $6 OFFSET $7"));
    assertThat(query.params().size(), is(7));
    assertThat(query.params().getString(0), is("isbn"));
    assertThat(query.params().getUUID(2), is(clusterId));
    assertThat(query.params().getInteger(5), is(20));
    assertThat(query.params().getInteger(6), is(40));
  }

  @Test
  void combinesRawWhereWithParameterizedWhere() {
    SqlBuilder.SqlQuery query = SqlBuilder.select("id")
        .from("s.global_records")
        .where("source_id = ?", "src")
        .andRaw("source_version = 1")
        .build();

    assertThat(query.sql(), is(
        "SELECT id FROM s.global_records WHERE source_id = $1 AND (source_version = 1)"));
    assertThat(query.params().size(), is(1));
  }

  @Test
  void rejectsMismatchedPlaceholderCount() {
    assertThrows(IllegalArgumentException.class,
        () -> SqlBuilder.select("id").from("t").where("a = ?", "one", "two"));
    assertThrows(IllegalArgumentException.class,
        () -> SqlBuilder.select("id").from("t").where("a = ? AND b = ?", "onlyOne"));
    assertThrows(IllegalArgumentException.class,
        () -> SqlBuilder.select("id").from("t").where("a = ?"));
  }
}
