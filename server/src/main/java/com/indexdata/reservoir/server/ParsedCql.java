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
import org.z3950.zing.cql.CQLParseException;
import org.z3950.zing.cql.CQLParser;
import org.z3950.zing.cql.CQLPrefixNode;
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
        if (computeNegatedSourceVersion && isConjunctive(root)
            && countSourceVersionEqualities(root) == 1) {
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

  /**
   * Return true if the tree only combines terms with AND (sort and prefix nodes allowed).
   * Negating a single equality is only equivalent to "the rest of this filter, other versions"
   * for such trees; under OR, NOT or PROX the rewritten filter is wrong.
   */
  private static boolean isConjunctive(CQLNode node) {
    return switch (node) {
      case CQLTermNode _ -> true;
      case CQLAndNode and -> isConjunctive(and.getLeftOperand())
          && isConjunctive(and.getRightOperand());
      case CQLPrefixNode prefix -> isConjunctive(prefix.getSubtree());
      case CQLSortNode sort -> isConjunctive(sort.getSubtree());
      default -> false;
    };
  }

  private static int countSourceVersionEqualities(CQLNode root) {
    int[] count = {0};
    root.traverse(new CQLDefaultNodeVisitor() {
      @Override
      public void onTermNode(CQLTermNode node) {
        if (isSourceVersionEquality(node)) {
          count[0]++;
        }
      }
    });
    return count[0];
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

  /**
   * Rewrite the single sourceVersion equality to {@code <>}; caller ensures a conjunctive tree.
   */
  private static CQLNode negateSourceVersionEquality(CQLNode node) {
    return switch (node) {
      case CQLTermNode term when isSourceVersionEquality(term) ->
          new CQLTermNode(term.getIndex(), new CQLRelation("<>"), term.getTerm());
      case CQLTermNode term -> term;
      case CQLAndNode and -> new CQLAndNode(
          negateSourceVersionEquality(and.getLeftOperand()),
          negateSourceVersionEquality(and.getRightOperand()),
          toModifierSet("and", and.getModifiers()));
      case CQLPrefixNode prefix -> new CQLPrefixNode(
          prefix.getPrefix().getName(), prefix.getPrefix().getIdentifier(),
          negateSourceVersionEquality(prefix.getSubtree()));
      case CQLSortNode sort -> {
        CQLSortNode copy = new CQLSortNode(negateSourceVersionEquality(sort.getSubtree()));
        sort.getSortIndexes().forEach(copy::addSortIndex);
        yield copy;
      }
      default -> throw new IllegalStateException(
          "Unexpected node in conjunctive tree: " + node.getClass().getSimpleName());
    };
  }

  private static ModifierSet toModifierSet(String base, java.util.List<Modifier> modifiers) {
    ModifierSet ms = new ModifierSet(base);
    for (Modifier modifier : modifiers) {
      ms.addModifier(modifier.getType(), modifier.getComparison(), modifier.getValue());
    }
    return ms;
  }
}
