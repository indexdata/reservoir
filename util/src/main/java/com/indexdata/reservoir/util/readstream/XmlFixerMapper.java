package org.folio.reservoir.util.readstream;

import io.vertx.core.buffer.Buffer;
import java.util.List;

public class XmlFixerMapper implements Mapper<Buffer, Buffer> {

  private static final String REPLACEMENT_CHAR = "&#xFFFD;";

  private static final int ASCII_LOOKAHED = 3;

  private static final List<String> PREDEFINED_ENTITIES
      = List.of("amp", "lt", "gt", "apos", "quot");

  private boolean ended;

  Buffer result;

  Buffer pending = null;

  private int numberOfFixes = 0;

  int front;

  int tail;

  int sequenceStart = -1;

  int sequenceLength = 0; // number bytes remaining in a UTF-8 sequence

  static boolean isAscii(byte b) {
    return (b & 128) == 0;
  }

  static boolean isContinuation(byte b) {
    return (b & 192) == 128;
  }

  static boolean is2byteSequence(byte b) {
    return (b & 224) == 192;
  }

  static boolean is3byteSequence(byte b) {
    return (b & 240) == 224;
  }

  static boolean is4byteSequence(byte b) {
    return (b & 248) == 240;
  }

  boolean handleSequence(Buffer input, byte leadingByte) {
    // the order of checks doesn't matter here but the most occurring
    // check is listed first. There will always be more continuation bytes
    // than leading bytes.
    if (isContinuation(leadingByte)) {
      if (sequenceLength == 0) {
        return addReplacement(input);
      }
      sequenceLength--;
      if (sequenceLength == 0) {
        flushSequence(input);
      }
      return false;
    }
    checkSkipSequence(input);
    if (!ended && front + 3 + ASCII_LOOKAHED >= input.length()) {
      incomplete(input);
      return true;
    }
    if (is2byteSequence(leadingByte)) {
      sequenceLength = 1;
    } else if (is3byteSequence(leadingByte)) {
      sequenceLength = 2;
    } else if (is4byteSequence(leadingByte)) {
      sequenceLength = 3;
    } else {
      addReplacement(input);
      return false;
    }
    sequenceStart = front;
    return false;
  }

  void checkSkipSequence(Buffer input) {
    if (sequenceLength > 0) {
      skipSequence(input);
    }
  }

  void skipSequence(Buffer input) {
    result.appendBuffer(input, tail, sequenceStart - tail);
    boolean lastWasReplaced = false;
    for (int i = sequenceStart; i < front; i++) {
      byte b = input.getByte(i);
      if (isAscii(b)) {
        result.appendByte(b);
        lastWasReplaced = false;
      } else if (!lastWasReplaced) {
        result.appendString(REPLACEMENT_CHAR);
        lastWasReplaced = true;
      }
    }
    tail = front;
    sequenceStart = -1;
    sequenceLength = 0;
    numberOfFixes++;
  }

  void flushSequence(Buffer input) {
    boolean fixes = false;
    result.appendBuffer(input, tail, sequenceStart - tail);
    for (int i = sequenceStart; i <= front; i++) {
      byte b = input.getByte(i);
      if (!isAscii(b)) {
        result.appendByte(b);
      }
    }
    for (int i = sequenceStart; i <= front; i++) {
      byte b = input.getByte(i);
      if (isAscii(b)) {
        result.appendByte(b);
        fixes = true;
      }
    }
    if (fixes) {
      numberOfFixes++;
    }
    tail = front + 1;
    sequenceStart = -1;
  }

  @Override
  public void push(Buffer buffer) {
    Buffer input;
    if (result == null) {
      result = Buffer.buffer();
    }
    // In most cases pending is null; especially for large buffers
    if (pending == null) {
      input = buffer;
    } else {
      pending.appendBuffer(buffer);
      input = pending;
    }
    tail = 0;
    for (front = 0; front < input.length(); front++) {
      byte leadingByte = input.getByte(front);
      if (!isAscii(leadingByte)) {
        if (handleSequence(input, leadingByte)) {
          return;
        }
      } else {
        if (sequenceLength > 0
            && front - sequenceStart >= sequenceLength + ASCII_LOOKAHED - 1) {
          skipSequence(input);
        }
        if (leadingByte < 32
            && leadingByte != '\t' && leadingByte != '\r' && leadingByte != '\n') {
          checkSkipSequence(input);
          addReplacement(input);
        } else if (leadingByte == '&') {
          checkSkipSequence(input);
          if (handleEntity(input)) {
            return;
          }
        }
      }
    }
    checkSkipSequence(input);
    result.appendBuffer(input, tail, front - tail);
    pending = null;
  }

  private boolean addReplacement(Buffer input) {
    result.appendBuffer(input, tail, front - tail);
    result.appendString(REPLACEMENT_CHAR);
    numberOfFixes++;
    tail = front + 1;
    return false;
  }

  boolean addReplacement(Buffer input, int newFront, int extraStart, int extraLength) {
    addReplacement(input);
    if (extraLength > 0) {
      result.appendBuffer(input, extraStart, extraLength);
    }
    front = newFront;
    tail = front + 1;
    return false;
  }

  private boolean handleEntity(Buffer input) {
    int j = front + 1;
    while (true) {
      if (j >= input.length()) {
        incomplete(input);
        return true;
      }
      byte b = input.getByte(j);
      if (b == ';') {
        break;
      }
      if (b <= ' ' || b > 'z') {
        // unfinished entity, replace & with replacement char
        return addReplacement(input);
      }
      j++;
    }
    byte b = input.getByte(front + 1);
    int skip = 0;
    while (!(b >= '0' && b <= '9' || b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z'
        || b == '#' || b == ';')) {
      if (skip == ASCII_LOOKAHED) {
        return addReplacement(input, j, 0, 0);
      }
      skip++;
      b = input.getByte(front + 1 + skip);
    }
    if (input.getByte(front + 1 + skip) == '#') {
      try {
        int v;
        if (input.getByte(front + 2 + skip) == 'x') {
          v = Integer.parseInt(input.getString(front + 3 + skip, j), 16);
        } else {
          v = Integer.parseInt(input.getString(front + 2 + skip, j));
        }
        if (v < 32 && v != '\t' && v != '\r' && v != '\n') {
          return addReplacement(input, j, front + 1, skip);
        }
      } catch (NumberFormatException e) {
        return addReplacement(input, j, front + 1, skip);
      }
    } else {
      String ent = input.getString(front + 1 + skip, j);
      if (!PREDEFINED_ENTITIES.contains(ent)) {
        return addReplacement(input, j, front + 1, skip);
      }
    }
    if (skip > 0) {
      numberOfFixes++;
      result.appendBuffer(input, tail, 1 + front - tail); // includes &
      result.appendBuffer(input, front + 1 + skip, j - (front + skip));
      result.appendBuffer(input, front + 1, skip);
      front = j;
      tail = front + 1;
    }
    return false;
  }

  private void incomplete(Buffer input) {
    if (ended) {
      result.appendBuffer(input, tail, input.length() - tail);
      pending = null;
    } else {
      result.appendBuffer(input, tail, front - tail);
      pending = Buffer.buffer();
      pending.appendBuffer(input, front, input.length() - front);
    }
  }

  @Override
  public Buffer poll() {
    if (pending != null) {
      return null;
    }
    Buffer ret = result;
    result = null;
    return ret;
  }

  @Override
  public void end() {
    ended = true;
    push(Buffer.buffer());
  }

  public int getNumberOfFixes() {
    return numberOfFixes;
  }
}
