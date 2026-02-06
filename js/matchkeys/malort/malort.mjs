// Generates a malort (singleton) match key.

/**
 * Generates deepdish match key.
 *
 * The functions apart from doStandardNum() are based on
 * the GoldRush specification December2024_0
 *
 * @version 1.0.0
 * @param {string} record - The MARC-in-JSON input string wrapped in {marc: ...} object.
 * @return {string} The matchkey. Components are gathered from relevant fields
 *     and concatenated to a long string.
 */
export function matchkey() {
  return crypto.randomUUID().toLowerCase();
}
