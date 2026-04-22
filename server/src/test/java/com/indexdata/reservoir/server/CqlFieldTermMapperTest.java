package com.indexdata.reservoir.server;

import org.folio.tlib.postgres.PgCqlDefinition;
import org.folio.tlib.postgres.PgCqlQuery;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldAlwaysMatches;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldText;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CqlFieldTermMapperTest {

  @Test
  void testMissingMapper() {
    var ret = Assertions.assertThrows(IllegalArgumentException.class, () -> new CqlFieldTermMapper(new PgCqlFieldAlwaysMatches(), null));
    Assertions.assertEquals("mapper cannot be null", ret.getMessage());
  }

  @Test
  void testMissingSub() {
    var ret = Assertions.assertThrows(IllegalArgumentException.class, () -> new CqlFieldTermMapper(null, (String s) -> s));
    Assertions.assertEquals("sub cannot be null", ret.getMessage());
  }

  @Test
  void testTextMapped() {
    var sub = new PgCqlFieldText().withExact().withColumn("basic");
    var fieldMapper = new CqlFieldTermMapper(sub, s -> s.replaceAll("-", ""));
    Assertions.assertEquals("basic", fieldMapper.getColumn());
    fieldMapper.withColumn("match_value");
    Assertions.assertEquals("match_value", fieldMapper.getColumn());
    PgCqlDefinition definition = PgCqlDefinition.create();
    definition.addField("isbn", fieldMapper);
    PgCqlQuery pgCqlQuery = definition.parse("isbn=978-3-16-148410-0");
    Assertions.assertEquals("match_value = '9783161484100'", pgCqlQuery.getWhereClause());
  }
}
