package org.folio.reservoir.util.readstream;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.marc4j.MarcPermissiveStreamReader;
import org.marc4j.MarcReader;
import org.marc4j.marc.Record;

public class Marc4jMapper implements Mapper<Buffer, Record> {
  private Buffer pendingBuffer = Buffer.buffer();

  private boolean ended;

  MarcReader marcReader;

  /**
   * Check if we have a complete MARC-record at buffer and return its length.
   * @param buffer memory buffer with presumably ISO2709 data
   * @param offset inspect at this offset and onwards.
   * @param ended this is the last buffer.
   * @return 0 if buffer is incomplete (not a complete MARC record); otherwise
   *     return length of MARC record at buffer at offset.
   * @throws DecodeException if not a MARC record at offset
   */
  static int parseMarcBuffer(Buffer buffer, int offset, boolean ended) {
    // skip up to 4 non-digit bytes (bad data)
    int remain = buffer.length() - offset;
    for (int i = 0; i < 4; i++) {
      if (remain < 5) {
        return ended ? remain : 0;
      }
      byte leadByte = buffer.getByte(offset);
      if (leadByte >= '0' && leadByte <= '9') {
        String lead = buffer.getString(offset, offset + 5);
        int length = Integer.parseInt(lead);
        if (length < 24) {
          throw new DecodeException("Bad MARC length");
        }
        if (remain >= length) {
          return length;
        }
        return ended ? remain : 0;
      }
      offset++;
      remain--;
    }
    throw new DecodeException("Missing MARC header");
  }

  @Override
  public Record poll() {
    if (marcReader != null) {
      if (marcReader.hasNext()) {
        return marcReader.next();
      }
      marcReader = null;
    }
    int sz = 0;
    while (true) {
      int add = parseMarcBuffer(pendingBuffer, sz, ended);
      if (add == 0) {
        break;
      }
      sz += add;
    }
    if (sz == 0) {
      return null;
    }
    try (
        InputStream inputStream = new ByteArrayInputStream(pendingBuffer.getBytes(0, sz));
    ) {
      marcReader = new MarcPermissiveStreamReader(inputStream, true, true);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      pendingBuffer = pendingBuffer.getBuffer(sz, pendingBuffer.length());
    }
    return marcReader.next();
  }

  @Override
  public void push(Buffer buffer) {
    this.pendingBuffer.appendBuffer(buffer);
  }

  @Override
  public void end() {
    ended = true;
  }

}
