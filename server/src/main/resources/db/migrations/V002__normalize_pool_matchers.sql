-- Normalize legacy pool matcher strings into canonical comma-separated form
-- (trimming whitespace around entries and dropping trailing '::').

UPDATE ${schema}.match_key_config
SET matcher = (
  SELECT string_agg(
      regexp_replace(btrim(p, E' \t\r\n'), '(::)+$', ''),
      ',' ORDER BY ord)
  FROM unnest(string_to_array(matcher, ',')) WITH ORDINALITY AS t(p, ord))
WHERE matcher ~ '(\s,|,\s|^\s|\s$|::(,|$))';
