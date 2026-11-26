package com.indexdata.reservoir.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.folio.tlib.postgres.PgCqlDefinition;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldText;
import org.junit.jupiter.api.Test;

class ParsedCqlTest {

  @Test
  void detectsUsedFieldsFromAstNotSubstrings() {
    PgCqlDefinition def = ReservoirService.createDefinitionBase();
    def.addField(CqlFields.POOL_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.POOL_ID.getQualifiedSqlName()));
    def.addField(CqlFields.SOURCE_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.SOURCE_ID.getQualifiedSqlName()));

    ParsedCql parsed = ParsedCql.parse(def, "poolId=\"sourceId=fake\"");
    assertThat(parsed.filtersOn(CqlFields.POOL_ID), is(true));
    assertThat(parsed.filtersOn(CqlFields.SOURCE_ID), is(false));
    assertThat(parsed.uses(CqlFields.SOURCE_ID), is(false));

    ParsedCql sortedOnly = ParsedCql.parse(def, "poolId=isbn sortBy sourceId");
    assertThat(sortedOnly.filtersOn(CqlFields.POOL_ID), is(true));
    assertThat(sortedOnly.filtersOn(CqlFields.SOURCE_ID), is(false));
    assertThat(sortedOnly.filtersOnGlobalRecords(), is(false));
    assertThat(sortedOnly.uses(CqlFields.SOURCE_ID), is(true));
    assertThat(sortedOnly.usesGlobalRecords(), is(true));
  }

  @Test
  void negatesSourceVersionEqualityStructurally() {
    PgCqlDefinition def = ReservoirService.createDefinitionGlobalRecords();

    ParsedCql withVersion = ParsedCql.parseWithSourceVersionNegation(
        def, "sourceId=SOURCE-1 AND sourceVersion=1");
    assertThat(withVersion.uses(CqlFields.SOURCE_VERSION), is(true));
    assertThat(withVersion.negatedSourceVersionWhere(), containsString("source_version<>1"));
    assertThat(withVersion.negatedSourceVersionWhere(), containsString("source_id = 'SOURCE-1'"));

    ParsedCql withoutVersion = ParsedCql.parseWithSourceVersionNegation(
        def, "sourceId=SOURCE-1");
    assertThat(withoutVersion.negatedSourceVersionWhere(), is(nullValue()));

    ParsedCql emptyVersion = ParsedCql.parseWithSourceVersionNegation(
        def, "sourceVersion=\"\"");
    assertThat(emptyVersion.negatedSourceVersionWhere(), is(nullValue()));
  }
}
