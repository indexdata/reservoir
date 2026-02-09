// Generates a malort (singleton) match key.

/**
 * Generates malort match key.
 *
 * @version 1.0.0
 * @param {string} record - The MARC-in-JSON input string wrapped in {marc: ...} object.
 * @return {string} The matchkey.
 */
export function matchkey() {
  return crypto.randomUUID().toLowerCase();
}
