package com.indexdata.reservoir.server;

import org.folio.tlib.postgres.PgCqlFieldType;
import org.z3950.zing.cql.CQLTermNode;

public class CqlFieldTermMapper implements PgCqlFieldType {

  public interface Mapper {
    String apply(String term);
  }

  final PgCqlFieldType sub;
  final Mapper mapper;

  /**
   * Create a new CqlFieldTermMapper.
   * @param sub the field type to delegate to after mapping the term
   * @param mapper the function to map the term
   */
  public CqlFieldTermMapper(PgCqlFieldType sub, Mapper mapper) {
    if (sub == null) {
      throw new IllegalArgumentException("sub cannot be null");
    }
    if (mapper == null) {
      throw new IllegalArgumentException("mapper cannot be null");
    }
    this.sub = sub;
    this.mapper = mapper;
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
    String term = mapper.apply(termNode.getTerm());
    CQLTermNode newTermNode = new CQLTermNode(termNode.getIndex(), termNode.getRelation(), term);
    return sub.handleTermNode(newTermNode);
  }

}
