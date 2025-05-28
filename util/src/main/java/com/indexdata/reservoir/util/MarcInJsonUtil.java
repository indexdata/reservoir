package org.folio.reservoir.util;

import static org.folio.reservoir.util.MarcConstants.FIELDS_LABEL;
import static org.folio.reservoir.util.MarcConstants.SUBFIELDS_LABEL;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Various utilities operating on MARC-in-JSON records.
 */
public final class MarcInJsonUtil {

  private MarcInJsonUtil() { }

  /**
   * Create MARC tag with indicators given.
   * @param marc MARC-in-JSON object
   * @param tag marc tag, such as "245"
   * @param ind1 indicator 1
   * @param ind2 indicator 2
   * @return fields array
   */
  public static JsonArray createMarcDataField(JsonObject marc, String tag,
      String ind1, String ind2) {

    JsonArray fields = marc.getJsonArray(FIELDS_LABEL);
    if (fields == null) {
      fields = new JsonArray();
      marc.put(FIELDS_LABEL, fields);
    }
    int i;
    for (i = 0; i < fields.size(); i++) {
      JsonObject field = fields.getJsonObject(i);
      int cmp = 1;
      for (String f : field.fieldNames()) {
        cmp = tag.compareTo(f);
        if (cmp <= 0) {
          break;
        }
      }
      if (cmp <= 0) {
        break;
      }
    }
    JsonObject field = new JsonObject();
    fields.add(i, new JsonObject().put(tag, field));
    field.put("ind1", ind1);
    field.put("ind2", ind2);
    JsonArray subfields = new JsonArray();
    field.put(SUBFIELDS_LABEL, subfields);
    return subfields;
  }

  /**
   * Lookup marc field.
   * @param marc MARC-in-JSON object
   * @param tag marc tag, such as "245"
   * @param ind1 indicator1 in match; null for any
   * @param ind2 indicator2 in match; null for any
   * @return subfields array if found; null otherwise
   */
  public static JsonArray lookupMarcDataField(JsonObject marc, String tag,
      String ind1, String ind2) {
    JsonArray fields = marc.getJsonArray(FIELDS_LABEL);
    if (fields == null) {
      return null;
    }
    for (int i = 0; i < fields.size(); i++) {
      JsonObject field = fields.getJsonObject(i);
      for (String f : field.fieldNames()) {
        if (f.equals(tag)) {
          Object tagObject = field.getValue(tag);
          if (tagObject instanceof String tagObjectString) {
            return new JsonArray().add(tagObjectString);
          }
          if (tagObject instanceof JsonObject tagJsonObject
              && (ind1 == null || ind1.equals(tagJsonObject.getString("ind1")))
              && (ind2 == null || ind2.equals(tagJsonObject.getString("ind2")))) {
            return tagJsonObject.getJsonArray(SUBFIELDS_LABEL);
          }
        }
      }
    }
    return null;
  }

  /**
   * Remove tag from record.
   * @param marc MARC-in-JSON object
   * @param tag fields with this tag are removed
   */
  public static void removeMarcField(JsonObject marc, String tag) {
    JsonArray fields = marc.getJsonArray(FIELDS_LABEL);
    if (fields == null) {
      return;
    }
    int i = 0;
    while (i < fields.size()) {
      JsonObject field = fields.getJsonObject(i);
      int cmp = 1;
      for (String f : field.fieldNames()) {
        cmp = tag.compareTo(f);
        if (cmp == 0) {
          break;
        }
      }
      if (cmp == 0) {
        fields.remove(i);
      } else {
        i++;
      }
    }
  }

}
