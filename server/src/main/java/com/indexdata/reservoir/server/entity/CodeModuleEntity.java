package com.indexdata.reservoir.server.entity;

/** Pure domain record representing a configured code module (JavaScript or JSONPath). */
@SuppressWarnings({"squid:S5738", "squid:S1123"})
public record CodeModuleEntity(
    String id,
    String type,
    String url,
    @Deprecated(forRemoval = true, since = "1.0") String function,
    String script
) {

  public CodeModuleEntity withScript(String resolvedScript) {
    return new CodeModuleEntity(id, type, url, function, resolvedScript);
  }

  public boolean hasUrl() {
    return url != null && !url.isEmpty();
  }

  public boolean hasScript() {
    return script != null && !script.isEmpty();
  }
}
