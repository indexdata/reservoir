
/**
 * Generates a malort (singleton) match key by sending an empty matchkey.
 *
 * @version 1.0.0
 * @param {string} record - The MARC-in-JSON input string wrapped in {marc: ...} object.
 * @return {string} The matchkey. This will be an empty string.
 * 
 */
export function matchkey() {
  return '';
}
