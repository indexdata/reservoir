package com.indexdata.reservoir.server;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.folio.tlib.postgres.PgCqlDefinition;
import org.folio.tlib.postgres.PgCqlQuery;
import org.z3950.zing.cql.CQLAndNode;
import org.z3950.zing.cql.CQLDefaultNodeVisitor;
import org.z3950.zing.cql.CQLNode;
import org.z3950.zing.cql.CQLNotNode;
import org.z3950.zing.cql.CQLOrNode;
import org.z3950.zing.cql.CQLParseException;
import org.z3950.zing.cql.CQLParser;
import org.z3950.zing.cql.CQLPrefixNode;
import org.z3950.zing.cql.CQLProxNode;
import org.z3950.zing.cql.CQLRelation;
import org.z3950.zing.cql.CQLSortNode;
import org.z3950.zing.cql.CQLTermNode;
import org.z3950.zing.cql.Modifier;
import org.z3950.zing.cql.ModifierSet;

/**
 * Structured result of parsing a CQL query, carrying SQL WHERE/ORDER BY clauses alongside the
 * exact sets of CQL fields inspected from search terms and sort keys in the AST (eliminating
 * fragile SQL string inspection).
 */
public record ParsedCql(
    String where,
    String orderBy,
    Set<CqlFields> filterFields,
    Set<CqlFields> sortFields,
    String negatedSourceVersionWhere
) {

  private static final Map<String, CqlFields> FIELDS_BY_LOWER_NAME = buildFieldLookup();

  public ParsedCql {
    filterFields = filterFields == null ? Set.of() : Set.copyOf(filterFields);
    sortFields = sortFields == null ? Set.of() : Set.copyOf(sortFields);
  }

  private static Map<String, CqlFields> buildFieldLookup() {
    Map<String, CqlFields> map = new HashMap<>();
    for (CqlFields field : CqlFields.values()) {
      map.put(field.getCqlName().toLowerCase(Locale.ROOT), field);
    }
    return Map.copyOf(map);
  }

  public static ParsedCql parse(PgCqlDefinition definition, String query) {
    return parse(definition, query, false);
  }

  private static ParsedCql parse(PgCqlDefinition definition, String query,
      boolean computeNegatedSourceVersion) {
    PgCqlQuery pgCqlQuery = definition.parse(query);
    String where = pgCqlQuery.getWhereClause();
    String orderBy = pgCqlQuery.getOrderByClause();
    EnumSet<CqlFields> filterFields = EnumSet.noneOf(CqlFields.class);
    EnumSet<CqlFields> sortFields = EnumSet.noneOf(CqlFields.class);
    String negatedWhere = null;
    if (query != null && !query.isBlank()) {
      try {
        CQLNode root = new CQLParser().parse(query);
        collectFields(root, filterFields, sortFields);
        if (computeNegatedSourceVersion && hasSourceVersionEquality(root)) {
          CQLNode negated = negateSourceVersionEquality(root);
          negatedWhere = definition.parse(negated.toCQL()).getWhereClause();
        }
      } catch (CQLParseException | IOException ignored) {
        // PgCqlDefinition.parse already succeeded or threw PgCqlException above.
      }
    }
    return new ParsedCql(where, orderBy, filterFields, sortFields, negatedWhere);
  }

  public static ParsedCql parseWithSourceVersionNegation(PgCqlDefinition definition, String query) {
    return parse(definition, query, true);
  }

  /** Return true if the given field appears in a search term (WHERE predicate). */
  public boolean filtersOn(CqlFields field) {
    return filterFields.contains(field);
  }

  /** Return true if any of the given fields appear in a search term (WHERE predicate). */
  public boolean filtersOnAny(CqlFields... fields) {
    for (CqlFields field : fields) {
      if (filterFields.contains(field)) {
        return true;
      }
    }
    return false;
  }

  /** Return true if any global_records field appears in a search term (WHERE predicate). */
  public boolean filtersOnGlobalRecords() {
    return filtersOnAny(CqlFields.GLOBAL_ID, CqlFields.LOCAL_ID,
        CqlFields.SOURCE_ID, CqlFields.SOURCE_VERSION);
  }

  /** Return true if the given field appears in either a search term or a sort key. */
  public boolean uses(CqlFields field) {
    return filterFields.contains(field) || sortFields.contains(field);
  }

  /** Return true if any of the given fields appear in a search term or a sort key. */
  public boolean usesAny(CqlFields... fields) {
    for (CqlFields field : fields) {
      if (uses(field)) {
        return true;
      }
    }
    return false;
  }

  /** Return true if any global_records field appears in a search term or a sort key. */
  public boolean usesGlobalRecords() {
    return usesAny(CqlFields.GLOBAL_ID, CqlFields.LOCAL_ID,
        CqlFields.SOURCE_ID, CqlFields.SOURCE_VERSION);
  }

  private static void collectFields(CQLNode root, EnumSet<CqlFields> filterFields,
      EnumSet<CqlFields> sortFields) {
    root.traverse(new CQLDefaultNodeVisitor() {
      @Override
      public void onTermNode(CQLTermNode node) {
        addIfKnown(node.getIndex(), filterFields);
      }

      @Override
      public void onSortNode(CQLSortNode node) {
        node.getSortIndexes().forEach(sortIndex -> addIfKnown(sortIndex.getBase(), sortFields));
      }
    });
  }

  private static void addIfKnown(String indexName, EnumSet<CqlFields> fields) {
    if (indexName == null) {
      return;
    }
    CqlFields field = FIELDS_BY_LOWER_NAME.get(indexName.toLowerCase(Locale.ROOT));
    if (field != null) {
      fields.add(field);
    }
  }

  private static boolean hasSourceVersionEquality(CQLNode root) {
    boolean[] found = {false};
    root.traverse(new CQLDefaultNodeVisitor() {
      @Override
      public void onTermNode(CQLTermNode node) {
        if (isSourceVersionEquality(node)) {
          found[0] = true;
        }
      }
    });
    return found[0];
  }

  private static boolean isSourceVersionEquality(CQLTermNode node) {
    if (!CqlFields.SOURCE_VERSION.getCqlName().equalsIgnoreCase(node.getIndex())) {
      return false;
    }
    if (node.getTerm() == null || node.getTerm().isEmpty()) {
      return false;
    }
    String base = node.getRelation().getBase();
    return "=".equals(base) || "==".equals(base);
  }

  private static CQLNode negateSourceVersionEquality(CQLNode node) {
    if (node instanceof CQLTermNode term) {
      if (isSourceVersionEquality(term)) {
        return new CQLTermNode(term.getIndex(), new CQLRelation("<>"), term.getTerm());
      }
      return term;
    }
    if (node instanceof CQLAndNode and) {
      return new CQLAndNode(
          negateSourceVersionEquality(and.getLeftOperand()),
          negateSourceVersionEquality(and.getRightOperand()),
          toModifierSet("and", and.getModifiers()));
    }
    if (node instanceof CQLOrNode or) {
      return new CQLOrNode(
          negateSourceVersionEquality(or.getLeftOperand()),
          negateSourceVersionEquality(or.getRightOperand()),
          toModifierSet("or", or.getModifiers()));
    }
    if (node instanceof CQLNotNode not) {
      return new CQLNotNode(
          negateSourceVersionEquality(not.getLeftOperand()),
          negateSourceVersionEquality(not.getRightOperand()),
          toModifierSet("not", not.getModifiers()));
    }
    if (node instanceof CQLProxNode prox) {
      return new CQLProxNode(
          negateSourceVersionEquality(prox.getLeftOperand()),
          negateSourceVersionEquality(prox.getRightOperand()),
          toModifierSet("prox", prox.getModifiers()));
    }
    if (node instanceof CQLPrefixNode prefix) {
      return new CQLPrefixNode(prefix.getPrefix().getName(), prefix.getPrefix().getIdentifier(),
          negateSourceVersionEquality(prefix.getSubtree()));
    }
    if (node instanceof CQLSortNode sort) {
      CQLSortNode copy = new CQLSortNode(negateSourceVersionEquality(sort.getSubtree()));
      sort.getSortIndexes().forEach(copy::addSortIndex);
      return copy;
    }
    return node;
  }

  private static ModifierSet toModifierSet(String base, java.util.List<Modifier> modifiers) {
    ModifierSet ms = new ModifierSet(base);
    for (Modifier modifier : modifiers) {
      ms.addModifier(modifier.getType(), modifier.getComparison(), modifier.getValue());
    }
    return ms;
  }
}
