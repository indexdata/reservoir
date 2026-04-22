package com.indexdata.reservoir.server;

import org.folio.tlib.postgres.PgCqlFieldType;
import org.z3950.zing.cql.CQLTermNode;

public class CqlFieldTermMapper implements PgCqlFieldType {

  public interface Mapper {
    String apply(String term);
  }

  final PgCqlFieldType sub;
  final Mapper termMapper;
  final Mapper sqlMapper;

  /**
   * Create a new CqlFieldTermMapper.
   * @param sub the field type to delegate to after mapping the term
   * @param termMapper the function to map the term
   * @param sqlMapper the function to map the SQL after the term is mapped
   */
  public CqlFieldTermMapper(PgCqlFieldType sub, Mapper termMapper, Mapper sqlMapper) {
    if (sub == null) {
      throw new IllegalArgumentException("sub cannot be null");
    }
    if (termMapper == null) {
      throw new IllegalArgumentException("termMapper cannot be null");
    }
    this.sub = sub;
    this.termMapper = termMapper;
    this.sqlMapper = sqlMapper;
  }

  @Override
  public PgCqlFieldType withColumn(String column) {
    sub.withColumn(column);
    return this;
  }

  @Override
  public String getColumn() {
    return sub.getColumn();
  }

  @Override
  public String handleTermNode(CQLTermNode termNode) {
    String term = termMapper.apply(termNode.getTerm());
    CQLTermNode newTermNode = new CQLTermNode(termNode.getIndex(), termNode.getRelation(), term);
    String sql = sub.handleTermNode(newTermNode);
    if (sqlMapper != null) {
      sql = sqlMapper.apply(sql);
    }
    return sql;
  }

}
