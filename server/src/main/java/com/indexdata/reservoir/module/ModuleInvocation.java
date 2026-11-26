package com.indexdata.reservoir.module;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Immutable reference to a code module and optional exported function ({@code module[::function]}).
 */
public record ModuleInvocation(String moduleName, String functionName) {

  public ModuleInvocation {
    if (moduleName == null || moduleName.isEmpty()) {
      throw new IllegalArgumentException("Module name cannot be empty");
    }
  }

  /**
   * Create module invocation by parsing {@code module[::function]}.
   * @param invocationString string of {@code module[::function]} where function is optional
   */
  public ModuleInvocation(String invocationString) {
    if (invocationString == null) {
      throw new IllegalArgumentException("Module invocation string cannot be null");
    }
    String[] parts = invocationString.split("::");
    if (parts.length == 0) {
      throw new IllegalArgumentException(
          "Malformed module invocation: '" + invocationString + "'");
    }
    this(parts[0], parts.length > 1 ? parts[1] : null);
  }

  public static ModuleInvocation parse(String invocationString) {
    return new ModuleInvocation(invocationString);
  }

  /**
   * Parse a comma-separated list of module invocations.
   * @param matcher comma-separated invocation list
   * @return immutable list of parsed invocations
   */
  public static List<ModuleInvocation> parseList(String matcher) {
    if (matcher == null) {
      throw new IllegalArgumentException("PoolConfig matcher is required");
    }
    String[] parts = matcher.split(",", -1);
    List<ModuleInvocation> result = new ArrayList<>(parts.length);
    for (String part : parts) {
      String trimmed = part.trim();
      if (trimmed.isEmpty()) {
        throw new IllegalArgumentException("Matcher module invocation cannot be empty");
      }
      result.add(new ModuleInvocation(trimmed));
    }
    return List.copyOf(result);
  }

  /**
   * Format a list of module invocations into the canonical {@code one,two} string.
   */
  public static String formatList(List<ModuleInvocation> invocations) {
    return invocations.stream()
        .map(ModuleInvocation::format)
        .collect(Collectors.joining(","));
  }

  /**
   * Parse and re-format a comma-separated matcher string into canonical {@code one,two} form.
   */
  public static String normalizeList(String matcher) {
    return formatList(parseList(matcher));
  }

  public String getModuleName() {
    return moduleName;
  }

  public String getFunctionName() {
    return functionName;
  }

  /**
   * Format back into the canonical {@code module} or {@code module::function} notation.
   */
  public String format() {
    return functionName == null ? moduleName : moduleName + "::" + functionName;
  }

  @Override
  public String toString() {
    return moduleName + "::" + functionName;
  }
}
