package org.folio.reservoir.module;

public class ModuleInvocation {
  private final String moduleName;
  private final String functionName;

  /**
   * Create module invocation by parsing the invocation string.
   * @param invocationString string of 'module<::function>' where <::function> is optional
   */
  public ModuleInvocation(String invocationString) {
    if (invocationString == null) {
      throw new IllegalArgumentException("Module invocation string cannot be null");
    }
    String[] parts = invocationString.split("::");
    if (parts.length == 1) {
      moduleName = parts[0];
      functionName = null;
    } else if (parts.length > 1) {
      moduleName = parts[0];
      functionName = parts[1];
    } else {
      throw new IllegalArgumentException(
        "Malformed module invocation: '" + invocationString + "'");
    }

  }

  /**
   * Get module name for this invocation.
   * @return the module name
   */
  public String getModuleName() {
    return moduleName;
  }

  /**
   * Get function name for this invocation.
   * @return the functio nName
   */
  public String getFunctionName() {
    return functionName;
  }

  /* (non-Javadoc)
   * @see java.lang.Object#toString()
   */
  @Override
  public String toString() {
    return moduleName + "::" + functionName;
  }
  
}
