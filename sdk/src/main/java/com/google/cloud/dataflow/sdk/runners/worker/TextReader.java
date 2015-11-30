/*******************************************************************************
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/

package com.google.cloud.dataflow.sdk.runners.worker;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.util.IOChannelFactory;
import com.google.cloud.dataflow.sdk.util.common.worker.ProgressTrackerGroup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.Collection;
import java.util.Iterator;

import javax.annotation.Nullable;

/**
 * A source that reads text files.
 *
 * @param <T> the type of the elements read from the source
 */
public class TextReader<T> extends FileBasedReader<T> {
  final boolean stripTrailingNewlines;
  final TextIO.CompressionType compressionType;

  public TextReader(String filepattern, boolean stripTrailingNewlines, @Nullable Long startPosition,
      @Nullable Long endPosition, Coder<T> coder, TextIO.CompressionType compressionType) {
    this(filepattern, stripTrailingNewlines, startPosition, endPosition, coder, true,
        compressionType);
  }

  protected TextReader(String filepattern, boolean stripTrailingNewlines,
      @Nullable Long startPosition, @Nullable Long endPosition, Coder<T> coder,
      boolean useDefaultBufferSize, TextIO.CompressionType compressionType) {
    super(filepattern, startPosition, endPosition, coder, useDefaultBufferSize);
    this.stripTrailingNewlines = stripTrailingNewlines;
    this.compressionType = compressionType;
  }

  public double getTotalParallelism() {
    try {
      if (compressionType == TextIO.CompressionType.UNCOMPRESSED) {
        // All files are splittable.
        return getTotalParallelismSplittable();
      } else if (compressionType == TextIO.CompressionType.AUTO) {
        for (String file : expandedFilepattern()) {
          if (FilenameBasedStreamFactory.getCompressionTypeForAuto(file)
              == TextIO.CompressionType.UNCOMPRESSED) {
            // At least one file is splittable.
            return getTotalParallelismSplittable();
          }
        }
        // All files were compressed.
        return getTotalParallelismUnsplittable();
      } else {
        // No compressed formats support liquid sharding yet.
        return getTotalParallelismUnsplittable();
      }
    } catch (IOException exn) {
      throw new RuntimeException(exn);
    }
  }

  private double getTotalParallelismSplittable() {
    // Assume splittable at every byte.
    return (endPosition == null ? Double.POSITIVE_INFINITY : endPosition)
        - (startPosition == null ? 0 : startPosition);
  }

  private double getTotalParallelismUnsplittable() throws IOException {
    // Total parallelism is the number of files matched by the filepattern.
    return expandedFilepattern().size();
  }

  @Override
  protected ReaderIterator<T> newReaderIteratorForRangeInFile(IOChannelFactory factory,
      String oneFile, long startPosition, @Nullable Long endPosition) throws IOException {
    // Position before the first record, so we can find the record beginning.
    final long start = startPosition > 0 ? startPosition - 1 : 0;

    TextFileIterator iterator = newReaderIteratorForRangeWithStrictStart(
        factory, oneFile, stripTrailingNewlines, start, endPosition);

    // Skip the initial record if start position was set.
    if (startPosition > 0) {
      iterator.hasNextImpl();
    }

    return iterator;
  }

  @Override
  protected ReaderIterator<T> newReaderIteratorForFiles(
      IOChannelFactory factory, Collection<String> files) throws IOException {
    if (files.size() == 1) {
      return newReaderIteratorForFile(factory, files.iterator().next(), stripTrailingNewlines);
    }

    return new TextFileMultiIterator(factory, files.iterator(), stripTrailingNewlines);
  }

  private TextFileIterator newReaderIteratorForFile(
      IOChannelFactory factory, String input, boolean stripTrailingNewlines) throws IOException {
    return newReaderIteratorForRangeWithStrictStart(factory, input, stripTrailingNewlines, 0, null);
  }

  /**
   * Returns a new iterator for lines in the given range in the given
   * file.  Does NOT skip the first line if the range starts in the
   * middle of a line (instead, the latter half that starts at
   * startOffset will be returned as the first element).
   */
  private TextFileIterator newReaderIteratorForRangeWithStrictStart(IOChannelFactory factory,
      String input, boolean stripTrailingNewlines, long startOffset, @Nullable Long endOffset)
      throws IOException {
    ReadableByteChannel reader = factory.open(input);
    if (!(reader instanceof SeekableByteChannel)) {
      throw new UnsupportedOperationException("Unable to seek in stream for " + input);
    }

    SeekableByteChannel seeker = (SeekableByteChannel) reader;

    return new TextFileIterator(
        new CopyableSeekableByteChannel(seeker), stripTrailingNewlines, startOffset, endOffset,
        new FileBasedReader.FilenameBasedStreamFactory(input, compressionType));
  }

  class TextFileMultiIterator extends LazyMultiReaderIterator<T> {
    private final IOChannelFactory factory;
    private final boolean stripTrailingNewlines;

    public TextFileMultiIterator(
        IOChannelFactory factory, Iterator<String> inputs, boolean stripTrailingNewlines) {
      super(inputs);
      this.factory = factory;
      this.stripTrailingNewlines = stripTrailingNewlines;
    }

    @Override
    protected ReaderIterator<T> open(String input) throws IOException {
      return newReaderIteratorForFile(factory, input, stripTrailingNewlines);
    }
  }

  class TextFileIterator extends FileBasedIterator {
    private ScanState state;

    TextFileIterator(CopyableSeekableByteChannel seeker, boolean stripTrailingNewlines,
        long startOffset, @Nullable Long endOffset,
        FileBasedReader.DecompressingStreamFactory compressionStreamFactory) throws IOException {
      super(seeker, startOffset, startOffset, endOffset,
          new ProgressTrackerGroup<Integer>() {
            @Override
            protected void report(Integer lineLength) {
              notifyElementRead(lineLength.longValue());
            }
          }.start(), compressionStreamFactory);

      this.state = new ScanState(BUF_SIZE, stripTrailingNewlines);
    }

    /**
     * Reads a line of text. A line is considered to be terminated by any
     * one of a line feed ({@code '\n'}), a carriage return
     * ({@code '\r'}), or a carriage return followed immediately by a linefeed
     * ({@code "\r\n"}).
     *
     * @return a {@code ByteArrayOutputStream} containing the contents of the
     *     line, with any line-termination characters stripped if
     *     stripTrailingNewlines==true, or {@code null} if the end of the stream has
     *     been reached.
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected ByteArrayOutputStream readElement() throws IOException {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(BUF_SIZE);

      int charsConsumed = 0;
      while (true) {
        // Attempt to read blocks of data at a time
        // until a separator is found.
        if (!state.readBytes(stream)) {
          break;
        }

        int consumed = state.consumeUntilSeparator(buffer);
        charsConsumed += consumed;
        if (consumed > 0 && state.separatorFound()) {
          if (state.lastByteRead() == '\r') {
            charsConsumed += state.copyCharIfLinefeed(buffer, stream);
          }
          break;
        }
      }

      if (charsConsumed == 0) {
        // Note that charsConsumed includes the size of any separators that may
        // have been stripped off -- so if we didn't get anything, we're at the
        // end of the file.
        return null;
      }

      offset += charsConsumed;
      return buffer;
    }
  }

  /**
   * ScanState encapsulates the state for the current buffer of text
   * being scanned.
   */
  private static class ScanState {
    private int start; // Valid bytes in buf start at this index
    private int pos; // Where the separator is in the buf (if one was found)
    private int end; // the index of the end of bytes in buf
    private final byte[] buf;
    private final boolean stripTrailingNewlines;
    private byte lastByteRead;

    public ScanState(int size, boolean stripTrailingNewlines) {
      this.start = 0;
      this.pos = 0;
      this.end = 0;
      this.buf = new byte[size];
      this.stripTrailingNewlines = stripTrailingNewlines;
    }

    public boolean readBytes(PushbackInputStream stream) throws IOException {
      if (start < end) {
        return true;
      }
      assert end <= buf.length : end + " > " + buf.length;
      int bytesRead = stream.read(buf, end, buf.length - end);
      if (bytesRead == -1) {
        return false;
      }
      end += bytesRead;
      return true;
    }

    /**
     * Consumes characters until a separator character is found or the
     * end of buffer is reached.
     *
     * <p>Updates the state to indicate the position of the separator
     * character. If pos==len, no separator was found.
     *
     * @return the number of characters consumed.
     */
    public int consumeUntilSeparator(ByteArrayOutputStream out) {
      for (pos = start; pos < end; ++pos) {
        lastByteRead = buf[pos];
        if (separatorFound()) {
          int charsConsumed = (pos - start + 1); // The separator is consumed
          copyToOutputBuffer(out);
          start = pos + 1; // skip the separator
          return charsConsumed;
        }
      }
      // No separator found
      assert pos == end;
      int charsConsumed = (pos - start);
      out.write(buf, start, charsConsumed);
      start = 0;
      end = 0;
      pos = 0;
      return charsConsumed;
    }

    public boolean separatorFound() {
      return lastByteRead == '\n' || lastByteRead == '\r';
    }

    public byte lastByteRead() {
      return buf[pos];
    }

    /**
     * Copies data from the input buffer to the output buffer.
     *
     * <p>If stripTrailing==false, line-termination characters are included in the copy.
     */
    private void copyToOutputBuffer(ByteArrayOutputStream out) {
      int charsCopied = pos - start;
      if (!stripTrailingNewlines && separatorFound()) {
        charsCopied++;
      }
      out.write(buf, start, charsCopied);
    }

    /**
     * Scans the input buffer to determine if a matched carriage return
     * has an accompanying linefeed and process the input buffer accordingly.
     *
     * <p>If stripTrailingNewlines==false and a linefeed character is detected,
     * it is included in the copy.
     *
     * @return the number of characters consumed
     */
    private int copyCharIfLinefeed(ByteArrayOutputStream out, PushbackInputStream stream)
        throws IOException {
      int charsConsumed = 0;
      // Check to make sure we don't go off the end of the buffer
      if ((pos + 1) < end) {
        if (buf[pos + 1] == '\n') {
          charsConsumed++;
          pos++;
          start++;
          if (!stripTrailingNewlines) {
            out.write('\n');
          }
        }
      } else {
        // We are at the end of the buffer and need one more
        // byte. Get it the slow but safe way.
        int b = stream.read();
        if (b == '\n') {
          charsConsumed++;
          if (!stripTrailingNewlines) {
            out.write(b);
          }
        } else if (b != -1) {
          // Consider replacing unread() since it may be slow if
          // iterators are cloned frequently.
          stream.unread(b);
        }
      }
      return charsConsumed;
    }
  }
}
