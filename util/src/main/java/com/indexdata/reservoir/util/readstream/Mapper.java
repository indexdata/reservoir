package org.folio.reservoir.util.readstream;

/**
 * Represents a FIFO queue for mapping elements from one type to another.
 * Implementations of this classed are to be used with the MappingReadStream
 */
public interface Mapper<T, V> {

  /**
   * Store the element for next mapping.
   * This is a blocking operation so the implementation should ensure that it returns quickly.
   * @param item element to be mapped
   */
  public void push(T item);

  /**
   * Retrieve the last mapped element. If the element is not ready, 'null' is returned.
   * @return mapped element
   */
  public V poll();

  /**
   * End-of-elements; push not called again.
   */
  public void end();

}
