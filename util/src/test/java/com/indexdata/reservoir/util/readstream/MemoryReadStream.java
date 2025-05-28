package org.folio.reservoir.util.readstream;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

public class MemoryReadStream implements ReadStream<Buffer> {
  final Buffer preBuffer;
  final Buffer repeatBuffer;
  final Buffer sepBuffer;
  final Buffer postBuffer;
  final Vertx vertx;
  final int no;
  int stage = -1;
  boolean sep;
  boolean paused;
  Handler<Buffer> handler;
  Handler<Void> endHandler;
  Handler<Throwable> exceptionHandler;

  public MemoryReadStream(Buffer preBuffer, Vertx vertx) {
    this(preBuffer, null, null, Buffer.buffer(), 0, vertx);
  }

  public MemoryReadStream(Buffer preBuffer, Buffer repeatBuffer, int no, Vertx vertx) {
    this(preBuffer, repeatBuffer, Buffer.buffer(), Buffer.buffer(), no, vertx);
  }

  public MemoryReadStream(Buffer preBuffer, Buffer repeatBuffer, Buffer sepBuffer, Buffer postBuffer, int no, Vertx vertx) {
    this.preBuffer = preBuffer;
    this.repeatBuffer = repeatBuffer;
    this.sepBuffer = sepBuffer;
    this.postBuffer = postBuffer;
    this.no = no;
    this.vertx = vertx;
  }

  public void run() {
    if (paused) {
      return;
    }
    vertx.runOnContext(x -> {
      if (paused) {
        return;
      }
      try {
        if (stage < 0) {
          stage++;
          handler.handle(preBuffer);
        } else if (stage >= no) {
          if (stage > no) {
            return;
          }
          stage++;
          handler.handle(postBuffer);
          endHandler.handle(null);
        } else if (sep) {
          handler.handle(sepBuffer);
          sep = false;
        } else {
          if (stage < no - 1) {
            sep = true;
          }
          stage++;
          handler.handle(repeatBuffer);
        }
      } catch (Exception e) {
        if (exceptionHandler != null) {
          exceptionHandler.handle(e);
        }
      }
      if (!paused) {
        vertx.runOnContext(y -> run());
      }
    });
  }

  @Override
  public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
    this.exceptionHandler = handler;
    return this;
  }

  @Override
  public ReadStream<Buffer> handler(Handler<Buffer> handler) {
    this.handler = handler;
    return this;
  }

  @Override
  public ReadStream<Buffer> pause() {
    paused = true;
    return this;
  }

  @Override
  public ReadStream<Buffer> resume() {
    if (paused) {
      paused = false;
      run();
    }
    return this;
  }

  @Override
  public ReadStream<Buffer> fetch(long l) {
    return this;
  }

  @Override
  public ReadStream<Buffer> endHandler(Handler<Void> handler) {
    this.endHandler = handler;
    return this;
  }
}
