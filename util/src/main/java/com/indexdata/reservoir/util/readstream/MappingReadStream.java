package org.folio.reservoir.util.readstream;

import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;

public class MappingReadStream<T,V> implements ReadStream<T>, Handler<V> {

  boolean emitting;

  long demand = Long.MAX_VALUE;

  boolean ended;

  protected final ReadStream<V> stream;

  Handler<T> eventHandler;

  Handler<Void> endHandler;

  Handler<Throwable> exceptionHandler;

  Mapper<V, T> mapper;

  /**
   * Wrap a read stream with a stream capable of applying mapper to the stream's elements.
   * @param stream stream to wrap
   * @param mapper mapper to apply
   */
  public MappingReadStream(ReadStream<V> stream, Mapper<V, T> mapper) {
    this.stream = stream;
    this.mapper = mapper;
    stream.handler(this);
    stream.endHandler(v -> {
      //might be already ended due to exception
      if (!ended) {
        end();
      }
    });
    stream.exceptionHandler(e -> {
      if (exceptionHandler != null) {
        exceptionHandler.handle(e);
      }
    });
  }

  @Override
  public ReadStream<T> exceptionHandler(Handler<Throwable> handler) {
    exceptionHandler = handler;
    return this;
  }

  @Override
  public ReadStream<T> handler(Handler<T> handler) {
    eventHandler = handler;
    return this;
  }

  @Override
  public ReadStream<T> pause() {
    demand = 0L;
    return this;
  }

  @Override
  public ReadStream<T> resume() {
    return fetch(Long.MAX_VALUE);
  }

  @Override
  public ReadStream<T> fetch(long l) {
    demand += l;
    if (demand < 0L) {
      demand = Long.MAX_VALUE;
    }
    checkPending();
    return this;
  }

  @Override
  public ReadStream<T> endHandler(Handler<Void> handler) {
    if (!ended) {
      endHandler = handler;
    }
    return this;
  }

  void end() {
    if (ended) {
      throw new IllegalStateException("Parsing already done");
    }
    ended = true;
    mapper.end();
    checkPending();
  }

  @Override
  public void handle(V event) {
    mapper.push(event);
    checkPending();
  }

  private void checkPending()  {
    if (emitting) {
      return;
    }
    emitting = true;
    try {
      while (demand > 0L) {
        T t = mapper.poll();
        if (t == null) {
          break;
        }
        if (demand != Long.MAX_VALUE) {
          --demand;
        }
        if (eventHandler != null) {
          eventHandler.handle(t);
        }
      }
      if (ended) {
        Handler<Void> handler = endHandler;
        endHandler = null;
        if (handler != null) {
          handler.handle(null);
        }
      } else {
        if (demand == 0L) {
          stream.pause();
        } else {
          stream.resume();
        }
      }
    } catch (Exception e) {
      // unregister so no more errors may be raised
      stream.handler(null);
      if (exceptionHandler != null) {
        exceptionHandler.handle(e);
      }
      // unregistering may suppress end handler
      // raise it and end imediately
      if (!ended) {
        ended = true;
        mapper.end();
        var handler = endHandler;
        endHandler = null;
        if (handler != null) {
          handler.handle(null);
        }
      }
    } finally {
      emitting = false;
    }
  }

}
