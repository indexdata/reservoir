package org.folio.reservoir.util;

import static org.folio.reservoir.util.EncodeXmlText.encodeXmlText;
import static org.folio.reservoir.util.MarcConstants.CODE_LABEL;
import static org.folio.reservoir.util.MarcConstants.CONTROLFIELD_LABEL;
import static org.folio.reservoir.util.MarcConstants.DATAFIELD_LABEL;
import static org.folio.reservoir.util.MarcConstants.FIELDS_LABEL;
import static org.folio.reservoir.util.MarcConstants.LEADER_LABEL;
import static org.folio.reservoir.util.MarcConstants.RECORD_LABEL;
import static org.folio.reservoir.util.MarcConstants.SUBFIELDS_LABEL;
import static org.folio.reservoir.util.MarcConstants.SUBFIELD_LABEL;
import static org.folio.reservoir.util.MarcConstants.TAG_LABEL;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MARC-in-JSON to MARCXML conversion.
 */
public final class JsonToMarcXml {

  private JsonToMarcXml() { }

  /** Convert MARC-in-JSON to MARCXML.
   *
   * @param obj MARC-in-JSON object
   * @return XML with record root element
   */
  public static String convert(JsonObject obj) {
    StringBuilder s = new StringBuilder();
    s.append("<" + RECORD_LABEL + " xmlns=\"http://www.loc.gov/MARC21/slim\">\n");
    String leader = obj.getString(LEADER_LABEL);
    if (leader != null) {
      s.append("  <" + LEADER_LABEL + ">" + encodeXmlText(leader) + "</" + LEADER_LABEL + ">\n");
    }
    JsonArray fields = obj.getJsonArray(FIELDS_LABEL);
    if (fields !=  null) {
      for (int i = 0; i < fields.size(); i++) {
        JsonObject control = fields.getJsonObject(i);
        control.fieldNames().forEach(f -> {
          Object fieldValue = control.getValue(f);
          if (fieldValue instanceof String string) {
            s.append("  <" + CONTROLFIELD_LABEL + " " + TAG_LABEL + "=\"");
            s.append(encodeXmlText(f));
            s.append("\">");
            s.append(encodeXmlText(string));
            s.append("</" + CONTROLFIELD_LABEL + ">\n");
          }
          if (fieldValue instanceof JsonObject fieldObject) {
            s.append("  <" + DATAFIELD_LABEL + " " + TAG_LABEL + "=\"");
            s.append(encodeXmlText(f));
            for (int j = 1; j <= 9; j++) { // ISO 2709 allows more than 2 indicators
              String indicatorValue = fieldObject.getString("ind" + j);
              if (indicatorValue != null) {
                s.append("\" ind" + j + "=\"");
                s.append(encodeXmlText(indicatorValue));
              }
            }
            s.append("\">\n");
            JsonArray subfields = fieldObject.getJsonArray(SUBFIELDS_LABEL);
            for (int j = 0; j < subfields.size(); j++) {
              JsonObject subfieldObject = subfields.getJsonObject(j);
              subfieldObject.fieldNames().forEach(sub -> {
                s.append("    <" + SUBFIELD_LABEL);
                s.append(" " + CODE_LABEL + "=\"" + encodeXmlText(sub) + "\">");
                s.append(encodeXmlText(subfieldObject.getString(sub)));
                s.append("</" + SUBFIELD_LABEL + ">\n");
              });
            }
            s.append("  </" + DATAFIELD_LABEL + ">\n");
          }
        });
      }
    }
    s.append("</record>");
    return s.toString();
  }

}
