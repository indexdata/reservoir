package org.folio.reservoir.server;

import org.graalvm.polyglot.Value;

public final class JavaScriptCheck {
  private JavaScriptCheck() {
    throw new UnsupportedOperationException("JavaScriptCheck");
  }

  /**
   * Run JavaScript compiler/interpreter once.
   */
  public static void check() {
    check("x => {"
        + "  const r = new RegExp('[0-9]');"
        + "  return '1'.match(r) ? 1 : 0;"
        + "}");
  }

  static void check(String script) {
    org.graalvm.polyglot.Context context = org.graalvm.polyglot.Context.create("js");
    Value func = context.eval("js", script);
    Value value = func.execute();
    boolean ok = value.isNumber() && value.asLong() == 1L;
    context.close();
    if (!ok) {
      throw new IllegalStateException("Unexpected result from javascript engine");
    }
  }
}
