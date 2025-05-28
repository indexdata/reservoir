package org.folio.reservoir.server;

import io.vertx.core.json.JsonObject;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

public class IngestStatsByFile {
  private List<Entry<String, IngestStats>> byFile = new LinkedList<>();

  private class ListEntry implements Entry<String, IngestStats> {
    private String key;
    private IngestStats value;

    public ListEntry(String key, IngestStats value) {
      this.key = key;
      this.value = value;
    }

    @Override
    public String getKey() {
      return key;
    }

    @Override
    public IngestStats getValue() {
      return value;
    }

    @Override
    public IngestStats setValue(IngestStats value) {
      this.value = value;
      return this.value;
    }

  }

  public IngestStatsByFile() {}

  public IngestStatsByFile(IngestStats stats) {
    addStats(stats);
  }

  public void addStats(IngestStats stats) {
    byFile.add(new ListEntry(stats.getFileName(), stats));
  }

  /**
   * Return JSON representation.
   * @return JSON representation
   */
  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    for (Entry<String, IngestStats> e : byFile) {
      json.put(e.getKey(), e.getValue().toJson());
    }
    return json;
  }

}
