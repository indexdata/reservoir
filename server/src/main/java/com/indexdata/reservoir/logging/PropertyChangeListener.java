package com.indexdata.reservoir.logging;

import java.util.EventListener;

/** Receives configuration changes from the shaded Log4j implementation. */
@FunctionalInterface
public interface PropertyChangeListener extends EventListener {
  void propertyChange(PropertyChangeEvent event);
}
