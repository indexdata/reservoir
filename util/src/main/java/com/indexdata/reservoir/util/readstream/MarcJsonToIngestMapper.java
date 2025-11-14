package com.indexdata.reservoir.util.readstream;

import com.indexdata.reservoir.util.MarcInJsonUtil;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Converts stream of JSON-in-MARC to payload JSON objects.
 *
 * <p>Records with 004 are treated as holdings records.
 */
public class MarcJsonToIngestMapper implements Mapper<JsonObject, JsonObject> {

  private boolean ended;

  List<JsonObject> marc = new LinkedList<>();

  protected final BiConsumer<Long, TimeUnit> timingConsumer;

  long startTime;

  public MarcJsonToIngestMapper(BiConsumer<Long, TimeUnit> timingConsumer) {
    this.timingConsumer = timingConsumer;
  }

  private void recordTime() {
    long currentTime = System.nanoTime();
    if (startTime != 0) {
      long duration = currentTime - startTime;
      if (timingConsumer != null) {
        timingConsumer.accept(duration, TimeUnit.NANOSECONDS);
      }
    }
    startTime = currentTime;
  }

  @Override
  public void push(JsonObject value) {
    recordTime();
    marc.add(value);
  }

  boolean isHolding(JsonObject marc) {
    return MarcInJsonUtil.lookupMarcDataField(marc, "004", null, null) != null;
  }

  static boolean getDeleted(JsonObject marc) {
    String leader = marc.getString("leader");
    return leader != null && leader.length() >= 24 && leader.charAt(5) == 'd';
  }

  static String getLocalId(JsonObject marc) {
    JsonArray fields = marc.getJsonArray("fields");
    if (fields == null || fields.isEmpty()) {
      return null;
    }
    return fields.getJsonObject(0).getString("001");
  }

  /**
   * Return next payload record (marc + optionally marcHoldings).
   * @return null if input is incomplete; payload JSON object otherwise.
   */
  // S:5413 'List.remove()' should not be used in ascending 'for' loops
  @java.lang.SuppressWarnings({"squid:S5413"})
  @Override
  public JsonObject poll() {
    if (marc.isEmpty()) {
      return null;
    }
    JsonObject parentMarc = marc.get(0);
    if (isHolding(parentMarc)) {
      throw new DecodeException("Parent MARC record is holding " + parentMarc.encodePrettily());
    }
    int marcSize = marc.size();
    int i = 1;
    while (i < marcSize && isHolding(marc.get(i))) {
      i++;
    }
    if (!ended && i == marcSize) {
      return null;
    }
    JsonObject payload = new JsonObject()
        .put("marc", parentMarc);
    JsonObject globalRecord = new JsonObject()
        .put("payload", payload);
    String localId = getLocalId(parentMarc);
    if (localId != null) {
      globalRecord.put("localId", localId.trim());
    }
    boolean deleted = getDeleted(parentMarc);
    if (deleted) {
      globalRecord.put("delete", true);
    }
    marc.remove(0); // remove the leader record
    if (i > 1) {
      JsonArray holdings = new JsonArray();
      for (int j = 1; j < i; j++) {
        // j < marcSize so this is safe S:5413
        holdings.add(marc.remove(0)); // remove each mfhd
      }
      payload.put("marcHoldings", holdings);
    }
    return globalRecord;
  }

  @Override
  public void end() {
    if (ended) {
      throw new IllegalStateException("Parsing already done");
    }
    recordTime();
    ended = true;
  }
}
