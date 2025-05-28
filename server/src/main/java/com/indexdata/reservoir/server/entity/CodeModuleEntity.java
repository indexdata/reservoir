package org.folio.reservoir.server.entity;

import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.util.Objects;

@SuppressWarnings({"squid:S5738","squid:S1123"})
public class CodeModuleEntity {
  private final String id;
  private final String type;
  private final String url;
  @Deprecated(forRemoval = true, since = "1.0")
  private final String function;
  private final String script;

  /**
   * Create code module entity from arguments.
   * @param id local id
   * @param url url to the module
   * @param function function exported by the module
   */
  public CodeModuleEntity(String id, String type, String url, String function, String script) {
    this.id = id;
    this.type = type;
    this.url = url;
    this.function = function;
    this.script = script;
  }
  
  /**
   * Return the id.
   * @return the id
   */
  public String getId() {
    return id;
  }
  
  /**
   * Type of the module, e.g jsonpath or javascript.
   * @return the type
   */
  public String getType() {
    return type;
  }

  /**
   * Return the url.
   * @return the url
   */
  public String getUrl() {
    return url;
  }
  
  /**
   * Return the function name.
   * @return the function
   */
  public String getFunction() {
    return function;
  }
  

  /**
   * Inline code script.
   * @return the script
   */
  public String getScript() {
    return script;
  }
  
  private static void put(JsonObject json, boolean omitNull, String key, Object value) {
    if (omitNull && value == null) {
      return;
    }
    json.put(key, value);
  }

  /**
   * Encode the entity as JSON.
   * @return JSON object
   */
  public JsonObject asJson() {
    return asJson(false);
  }

  /**
   * Encode the entity as JSON.
   * @omitNull omit null values if true
   * @return JSON object
   */
  public JsonObject asJson(boolean omitNull) {
    JsonObject json = new JsonObject();
    put(json, omitNull, CodeModuleBuilder.ID_FIELD, id);
    put(json, omitNull, CodeModuleBuilder.TYPE_FIELD, type);
    put(json, omitNull, CodeModuleBuilder.URL_FIELD, url);
    put(json, omitNull, CodeModuleBuilder.FUNCTION_FIELD, function);
    put(json, omitNull, CodeModuleBuilder.SCRIPT_FIELD, script);
    return json;
  }
  
  /**
   * Encode the entity as Tuple.
   * @return Tuple object
   */
  public Tuple asTuple() {
    return Tuple.of(id, type, url, function, script);
  }


  /* (non-Javadoc)
   * @see java.lang.Object#hashCode()
   */
  
  @Override
  public int hashCode() {
    return Objects.hash(id, type, url, function, script);
  }

  /* (non-Javadoc)
   * @see java.lang.Object#equals(java.lang.Object)
   */
  
  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    CodeModuleEntity other = (CodeModuleEntity) obj;
    return Objects.equals(id, other.id) 
        && Objects.equals(type, other.type) 
        && Objects.equals(url, other.url)
        && Objects.equals(function, other.function) 
        && Objects.equals(script, other.script);
  }

  public static class CodeModuleBuilder {

    public static final String ID_FIELD = "id";

    public static final String TYPE_FIELD = "type";
    
    public static final String URL_FIELD = "url";

    @Deprecated(forRemoval = true, since = "1.0")
    public static final String FUNCTION_FIELD = "function";
    
    public static final String SCRIPT_FIELD = "script";

    private final JsonObject json;

    public CodeModuleBuilder(String id) {
      json = new JsonObject();
      json.put(ID_FIELD, id);
    }

    public CodeModuleBuilder(JsonObject source) {
      json = asJson(source);
    }

    public CodeModuleBuilder(Row row) {
      json = asJson(row);
    }

    public CodeModuleBuilder type(String type) {
      json.put(TYPE_FIELD, type);
      return this;
    }

    public CodeModuleBuilder url(String url) {
      json.put(URL_FIELD, url);
      return this;
    }

    public CodeModuleBuilder function(String function) {
      json.put(FUNCTION_FIELD, function);
      return this;
    }

    public CodeModuleBuilder script(String script) {
      json.put(SCRIPT_FIELD, script);
      return this;
    }

    /**
     * Build the entity.
     * @return entity
     */
    public CodeModuleEntity build() {
      return new CodeModuleEntity(
        json.getString(ID_FIELD),
        json.getString(TYPE_FIELD),
        json.getString(URL_FIELD),
        json.getString(FUNCTION_FIELD),
        json.getString(SCRIPT_FIELD)
      );
    }

    /*
     * A shortcut to get JSON direclty from the builder.
     */
    public JsonObject buildJson() {
      return json;
    }

    /**
     * Encodes a single code module row as JSON.
     * @param row code module row
     * @return JSON object
     */
    public static JsonObject asJson(Row row) {
      return new JsonObject()
        .put(ID_FIELD, row.getString(ID_FIELD))
        .put(TYPE_FIELD, row.getString(TYPE_FIELD))
        .put(URL_FIELD, row.getString(URL_FIELD))
        .put(FUNCTION_FIELD, row.getString(FUNCTION_FIELD))
        .put(SCRIPT_FIELD, row.getString(SCRIPT_FIELD));
    }

    /**
     * Coopies the relevat parts of the input JSON as output.
     * @param source input json
     * @return new JSON output
     */
    public static JsonObject asJson(JsonObject source) {
      return new JsonObject()
        .put(ID_FIELD, source.getString(ID_FIELD))
        .put(TYPE_FIELD, source.getString(TYPE_FIELD))
        .put(URL_FIELD, source.getString(URL_FIELD))
        .put(FUNCTION_FIELD, source.getString(FUNCTION_FIELD))
        .put(SCRIPT_FIELD, source.getString(SCRIPT_FIELD));
    }


  }



}
