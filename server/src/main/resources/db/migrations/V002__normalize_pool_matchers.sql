-- Normalize stored pool matcher strings to exactly what ModuleInvocation.normalizeList produces,
-- so exact `matcher==` filters (which normalize the query term) match legacy rows.
--
-- Mirrors the Java parser: each comma-separated entry is trimmed and split on '::'; the first part
-- is the module and the second part, if non-empty, is the function. Empty functions and anything
-- after the second part are dropped. Keep in sync with ModuleInvocation (SchemaMigratorTest checks
-- parity and idempotency).

UPDATE ${schema}.match_key_config AS c
SET matcher = n.normalized
FROM (
  SELECT k.id,
         string_agg(
           coalesce(e.parts[1], '')
           || CASE WHEN nullif(e.parts[2], '') IS NOT NULL
                   THEN '::' || e.parts[2] ELSE '' END,
           ',' ORDER BY t.ord) AS normalized
  FROM ${schema}.match_key_config AS k
  CROSS JOIN LATERAL unnest(string_to_array(k.matcher, ',')) WITH ORDINALITY AS t(p, ord)
  CROSS JOIN LATERAL (SELECT string_to_array(btrim(t.p, E' \t\r\n'), '::') AS parts) AS e
  WHERE k.matcher IS NOT NULL
  GROUP BY k.id
) AS n
WHERE c.id = n.id AND c.matcher IS DISTINCT FROM n.normalized;
