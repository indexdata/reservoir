package com.indexdata.reservoir.logging;

import java.util.EventObject;

/**
 * Configuration-change event used by the shaded Log4j implementation.
 * Maven Shade redirects Log4j's java.beans references here in the server fat JAR.
 */
public class PropertyChangeEvent extends EventObject {
  private static final long serialVersionUID = 1L;

  private final String propertyName;
  private final Object oldValue;
  private final Object newValue;
  private Object propagationId;

  /** Creates an event describing a property change. */
  public PropertyChangeEvent(Object source, String propertyName, Object oldValue, Object newValue) {
    super(source);
    this.propertyName = propertyName;
    this.oldValue = oldValue;
    this.newValue = newValue;
  }

  public String getPropertyName() {
    return propertyName;
  }

  public Object getOldValue() {
    return oldValue;
  }

  public Object getNewValue() {
    return newValue;
  }

  public Object getPropagationId() {
    return propagationId;
  }

  public void setPropagationId(Object propagationId) {
    this.propagationId = propagationId;
  }
}
