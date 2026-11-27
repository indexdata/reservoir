package com.indexdata.reservoir.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.folio.tlib.postgres.PgCqlDefinition;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldAlwaysMatches;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldText;
import org.junit.jupiter.api.Test;

class ReservoirServiceTest {

  private static PgCqlDefinition testCqlDefinition() {
    PgCqlDefinition def = PgCqlDefinition.create();
    def.addField(CqlFields.CQL_ALL_RECORDS.getCqlName(), new PgCqlFieldAlwaysMatches());
    def.addField(CqlFields.POOL_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.POOL_ID.getQualifiedSqlName()));
    def.addField(CqlFields.MATCHKEY_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.MATCHKEY_ID.getQualifiedSqlName()));
    def.addField(CqlFields.SOURCE_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.SOURCE_ID.getQualifiedSqlName()));
    return def;
  }

  private static boolean usesMatchkeyId(String query) {
    return ParsedCql.parse(testCqlDefinition(), query).uses(CqlFields.MATCHKEY_ID);
  }

  @Test
  void detectsDeprecatedCqlIndex() {
    assertTrue(usesMatchkeyId("matchkeyId=isbn AND sourceId=source"));
    assertTrue(usesMatchkeyId("sourceId=source sortby matchkeyId"));
    assertTrue(usesMatchkeyId("poolId=isbn AND matchkeyId=legacy"));
    assertTrue(usesMatchkeyId("MATCHKEYID=isbn"));
  }

  @Test
  void ignoresCqlTermContainingDeprecatedIndexName() {
    assertFalse(usesMatchkeyId("poolId=\"matchkeyId=isbn\""));
    assertFalse(usesMatchkeyId("poolId=isbn AND sourceId=source"));
    assertFalse(usesMatchkeyId(null));
  }

  @Test
  void sanitizesUserAgentForLogging() {
    assertEquals("-", ReservoirService.sanitizeUserAgent(null));
    assertEquals("-", ReservoirService.sanitizeUserAgent("  "));
    assertEquals("client injected", ReservoirService.sanitizeUserAgent("client\ninjected"));
    assertEquals(256, ReservoirService.sanitizeUserAgent("x".repeat(300)).length());
  }
}
