/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.io;

import com.google.api.client.util.Preconditions;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.util.ExecutionContext;
import com.google.cloud.dataflow.sdk.util.IOChannelFactory;
import com.google.cloud.dataflow.sdk.util.IOChannelUtils;
import com.google.common.collect.ImmutableList;

import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;

/**
 * A common base class for all file-based {@link Source}s. Extend this class to implement your own
 * file-based custom source.
 *
 * <p>A file-based {@code Source} is a {@code Source} backed by a file pattern defined as a Java
 * glob, a single file, or a offset range for a single file. See {@link ByteOffsetBasedSource} for
 * semantics of offset ranges.
 *
 * <p>This source stores a {@code String} that is an {@link IOChannelFactory} specification for a
 * file or file pattern. There should be an {@code IOChannelFactory} defined for the file
 * specification provided. Please refer to {@link IOChannelUtils} and {@link IOChannelFactory} for
 * more information on this.
 *
 * <p>In addition to the methods left abstract from {@code Source}, subclasses must implement
 * methods to create a sub-source and a reader for a range of a single file -
 * {@link #createForSubrangeOfFile} and {@link #createSingleFileReader}. Please refer to
 * {@link XmlSource} for an example implementation of {@code FileBasedSource}.
 *
 * @param <T> Type of records represented by the source.
 */
public abstract class FileBasedSource<T> extends ByteOffsetBasedSource<T> {
  private static final long serialVersionUID = 0;
  private static final Logger LOG = LoggerFactory.getLogger(FileBasedSource.class);

  private final String fileOrPatternSpec;
  private final Mode mode;

  /**
   * A given {@code FileBasedSource} represents a file resource of one of these types.
   */
  public enum Mode {
    FILEPATTERN,
    SINGLE_FILE_OR_SUBRANGE
  }

  /**
   * Create a {@code FileBaseSource} based on a file or a file pattern specification. This
   * constructor must be used when creating a new {@code FileBasedSource} for a file pattern.
   *
   * <p> See {@link ByteOffsetBasedSource} for a detailed description of {@code minBundleSize}.
   *
   * @param fileOrPatternSpec {@link IOChannelFactory} specification of file or file pattern
   *        represented by the {@link FileBasedSource}.
   * @param minBundleSize minimum bundle size in bytes.
   */
  public FileBasedSource(String fileOrPatternSpec, long minBundleSize) {
    super(0, Long.MAX_VALUE, minBundleSize);
    mode = Mode.FILEPATTERN;
    this.fileOrPatternSpec = fileOrPatternSpec;
  }

  /**
   * Create a {@code FileBasedSource} based on a single file. This constructor must be used when
   * creating a new {@code FileBasedSource} for a subrange of a single file.
   * Additionally, this constructor must be used to create new {@code FileBasedSource}s when
   * subclasses implement the method {@link #createForSubrangeOfFile}.
   *
   * <p> See {@link ByteOffsetBasedSource} for detailed descriptions of {@code minBundleSize},
   * {@code startOffset}, and {@code endOffset}.
   *
   * @param fileName {@link IOChannelFactory} specification of the file represented by the
   *        {@link FileBasedSource}.
   * @param minBundleSize minimum bundle size in bytes.
   * @param startOffset starting byte offset.
   * @param endOffset ending byte offset. If the specified value {@code >= #getMaxEndOffset()} it
   *        implies {@code #getMaxEndOffSet()}.
   */
  public FileBasedSource(String fileName, long minBundleSize,
      long startOffset, long endOffset) {
    super(startOffset, endOffset, minBundleSize);
    mode = Mode.SINGLE_FILE_OR_SUBRANGE;
    this.fileOrPatternSpec = fileName;
  }

  public final String getFileOrPatternSpec() {
    return fileOrPatternSpec;
  }

  public final Mode getMode() {
    return mode;
  }

  @Override
  public final FileBasedSource<T> createSourceForSubrange(long start, long end) {
    Preconditions.checkArgument(mode != Mode.FILEPATTERN,
        "Cannot split a file pattern based source based on positions");
    Preconditions.checkArgument(start >= getStartOffset(), "Start offset value " + start
        + " of the subrange cannot be smaller than the start offset value " + getStartOffset()
        + " of the parent source");
    Preconditions.checkArgument(end <= getEndOffset(), "End offset value " + end
        + " of the subrange cannot be larger than the end offset value " + getEndOffset()
        + " of the parent source");

    FileBasedSource<T> source = createForSubrangeOfFile(fileOrPatternSpec, start, end);
    if (start > 0 || end != Long.MAX_VALUE) {
      Preconditions.checkArgument(source.getMode() == Mode.SINGLE_FILE_OR_SUBRANGE,
          "Source created for the range [" + start + "," + end + ")"
          + " must be a subrange source");
    }
    return source;
  }

  /**
   * Creates and returns a new {@code FileBasedSource} of the same type as the current
   * {@code FileBasedSource} backed by a given file and an offset range. When current source is
   * being split, this method is used to generate new sub-sources. When creating the source
   * subclasses must call the constructor {@link #FileBasedSource(String, long, long, long)} of
   * {@code FileBasedSource} with corresponding parameter values passed here.
   *
   * @param fileName file backing the new {@code FileBasedSource}.
   * @param start starting byte offset of the new {@code FileBasedSource}.
   * @param end ending byte offset of the new {@code FileBasedSource}. May be Long.MAX_VALUE,
   *        in which case it will be inferred using {@link #getMaxEndOffset}.
   */
  public abstract FileBasedSource<T> createForSubrangeOfFile(String fileName, long start, long end);

  /**
   * Creates and returns an instance of a {@code FileBasedReader} implementation for the current
   * source assuming the source represents a single file. File patterns will be handled by
   * {@code FileBasedSource} implementation automatically.
   */
  public abstract FileBasedReader<T> createSingleFileReader(PipelineOptions options,
                                                            ExecutionContext executionContext);

  @Override
  public final long getEstimatedSizeBytes(PipelineOptions options) throws Exception {
    // This implementation of method getEstimatedSizeBytes is provided to simplify subclasses. Here
    // we perform the size estimation of files and file patterns using the interface provided by
    // IOChannelFactory.

    IOChannelFactory factory = IOChannelUtils.getFactory(fileOrPatternSpec);
    if (mode == Mode.FILEPATTERN) {
      // TODO Implement a more efficient parallel/batch size estimation mechanism for file patterns.
      long startTime = System.currentTimeMillis();
      long totalSize = 0;
      Collection<String> inputs = factory.match(fileOrPatternSpec);
      for (String input : inputs) {
        totalSize += factory.getSizeBytes(input);
      }
      LOG.debug("Size estimation of file pattern " + fileOrPatternSpec + " took "
          + (System.currentTimeMillis() - startTime) + " ms");
      return totalSize;
    } else {
      long start = getStartOffset();
      long end = Math.min(getEndOffset(), getMaxEndOffset(options));
      return end - start;
    }
  }

  @Override
  public final List<? extends FileBasedSource<T>> splitIntoBundles(long desiredBundleSizeBytes,
      PipelineOptions options) throws Exception {
    // This implementation of method splitIntoBundles is provided to simplify subclasses. Here we
    // split a FileBasedSource based on a file pattern to FileBasedSources based on full single
    // files. For files that can be efficiently seeked, we further split FileBasedSources based on
    // those files to FileBasedSources based on sub ranges of single files.

    if (mode == Mode.FILEPATTERN) {
      long startTime = System.currentTimeMillis();
      List<FileBasedSource<T>> splitResults = new ArrayList<>();
      for (String file : FileBasedSource.expandFilePattern(fileOrPatternSpec)) {
        splitResults.addAll(createForSubrangeOfFile(file, 0, Long.MAX_VALUE).splitIntoBundles(
            desiredBundleSizeBytes, options));
      }
      LOG.debug("Splitting the source based on file pattern " + fileOrPatternSpec + " took "
          + (System.currentTimeMillis() - startTime) + " ms");
      return splitResults;
    } else {
      if (isSplittable()) {
        List<FileBasedSource<T>> splitResults = new ArrayList<>();
        for (ByteOffsetBasedSource<T> split :
            super.splitIntoBundles(desiredBundleSizeBytes, options)) {
          splitResults.add((FileBasedSource<T>) split);
        }
        return splitResults;
      } else {
        LOG.debug("The source for file " + fileOrPatternSpec
            + " is not split into sub-range based sources since the file is not seekable");
        return ImmutableList.of(this);
      }
    }
  }

  /**
   * Determines whether a file represented by this source is can be split into bundles.
   *
   * <p>By default, a file is splittable if it is on a file system that supports efficient read
   * seeking. Subclasses may override to provide different behavior.
   */
  protected boolean isSplittable() throws Exception {
    // We split a file-based source into subranges only if the file is efficiently seekable.
    // If a file is not efficiently seekable it would be highly inefficient to create and read a
    // source based on a subrange of that file.
    IOChannelFactory factory = IOChannelUtils.getFactory(fileOrPatternSpec);
    return factory.isReadSeekEfficient(fileOrPatternSpec);
  }

  @Override
  public final BoundedReader<T> createReader(PipelineOptions options,
                                             ExecutionContext executionContext) throws IOException {
    // Validate the current source prior to creating a reader for it.
    this.validate();

    if (mode == Mode.FILEPATTERN) {
      long startTime = System.currentTimeMillis();
      Collection<String> files = FileBasedSource.expandFilePattern(fileOrPatternSpec);
      List<FileBasedReader<T>> fileReaders = new ArrayList<>();
      for (String fileName : files) {
        long endOffset;
        try {
          endOffset = IOChannelUtils.getFactory(fileName).getSizeBytes(fileName);
        } catch (IOException e) {
          LOG.warn("Failed to get size of " + fileName, e);
          endOffset = Long.MAX_VALUE;
        }
        fileReaders.add(createForSubrangeOfFile(fileName, 0, endOffset).createSingleFileReader(
            options, executionContext));
      }
      LOG.debug("Creating a reader for file pattern " + fileOrPatternSpec + " took "
          + (System.currentTimeMillis() - startTime) + " ms");
      return new FilePatternReader(this, fileReaders);
    } else {
      return createSingleFileReader(options, executionContext);
    }
  }

  @Override
  public String toString() {
    switch (mode) {
      case FILEPATTERN:
        return fileOrPatternSpec;
      case SINGLE_FILE_OR_SUBRANGE:
        return fileOrPatternSpec + " range " + super.toString();
      default:
        throw new IllegalStateException("Unexpected mode: " + mode);
    }
  }

  @Override
  public void validate() {
    super.validate();
    switch (mode) {
      case FILEPATTERN:
        Preconditions.checkArgument(getStartOffset() == 0,
            "FileBasedSource is based on a file pattern or a full single file "
            + "but the starting offset proposed " + getStartOffset() + " is not zero");
        Preconditions.checkArgument(getEndOffset() == Long.MAX_VALUE,
            "FileBasedSource is based on a file pattern or a full single file "
            + "but the ending offset proposed " + getEndOffset() + " is not Long.MAX_VALUE");
        break;
      case SINGLE_FILE_OR_SUBRANGE:
        // Nothing more to validate.
        break;
      default:
        throw new IllegalStateException("Unknown mode: " + mode);
    }
  }

  @Override
  public final long getMaxEndOffset(PipelineOptions options) throws Exception {
    if (mode == Mode.FILEPATTERN) {
      throw new IllegalArgumentException("Cannot determine the exact end offset of a file pattern");
    }
    if (getEndOffset() == Long.MAX_VALUE) {
      IOChannelFactory factory = IOChannelUtils.getFactory(fileOrPatternSpec);
      return factory.getSizeBytes(fileOrPatternSpec);
    } else {
      return getEndOffset();
    }
  }

  private static Collection<String> expandFilePattern(String fileOrPatternSpec) throws IOException {
    IOChannelFactory factory = IOChannelUtils.getFactory(fileOrPatternSpec);
    return factory.match(fileOrPatternSpec);
  }

  /**
   * A {@link Source.Reader reader} that implements code common to readers of
   * {@code FileBasedSource}s.
   *
   * <h2>Seekability</h2>
   *
   * <p>This reader uses a {@link ReadableByteChannel} created for the file represented by the
   * corresponding source to efficiently move to the correct starting position defined in the
   * source. Subclasses of this reader should implement {@link #startReading} to get access to this
   * channel. If the source corresponding to the reader is for a subrange of a file the
   * {@code ReadableByteChannel} provided is guaranteed to be an instance of the type
   * {@link SeekableByteChannel}, which may be used by subclass to traverse back in the channel to
   * determine the correct starting position.
   *
   * <h2>Split Points</h2>
   *
   * <p>Simple record-based formats (such as reading lines, reading CSV etc.), where each record can
   * be identified by a unique offset, should interpret a range [A, B) as "read from the first
   * record starting at or after offset A, up to but not including the first record starting at or
   * after offset B".
   *
   * <p>More complex formats, such as some block-based formats, may have records that are not
   * directly addressable: i.e., for some records, there is no way to describe the location of a
   * record using a single offset number. For example, imagine a file format consisting of a
   * sequence of blocks, where each block is compressed using some block compression algorithm. Then
   * blocks have offsets, but individual records don't. More complex cases are also possible.
   *
   * <p>Many such formats still admit reading a range of offsets in a way consistent with the
   * semantics of {@code ByteOffsetBasedReader}, i.e. reading [A, B) and [B, C) is equivalent to
   * reading [A, C). E.g., for the compressed block-based format discussed above, reading [A, B)
   * would mean "read all the records in all blocks whose starting offset is in [A, B)".
   *
   * <p>To support such complex formats in {@code FileBasedReader}, we introduce the notion of
   * <i>split points</i>. We say that a record is a split point if there exists an offset A such
   * that the record is the first one to be read for a range [A, {@code Long.MAX_VALUE}). E.g. for
   * the block-based format above, the only split points would be the first records in each block.
   *
   * <p>With the above definition of split points an extended definition of the offset of a record
   * can be specified. For a record that is at a split point, its offset is defined to be the
   * largest A such that reading a source with the range [A, Long.MAX_VALUE) includes this record;
   * offsets of other records are only required to be non-strictly increasing. Offsets of records of
   * a {@code FileBasedReader} should be set based on this definition.
   *
   * <h2>Reading Records</h2>
   *
   * <p>Sequential reading is implemented using {@link #readNextRecord}.
   *
   * <p>Then {@code FileBasedReader} implements "reading a range [A, B)" in the following way.
   * <ol>
   * <li>{@code start()} opens the file
   * <li>{@code start()} seeks the {@code SeekableByteChannel} to A (reading offset ranges for
   * non-seekable files is not supported) and calls {@code startReading()}
   * <li>the subclass must do whatever is needed to move to the first split point at or after this
   * position in the channel
   * <li>{@code start()} calls {@code advance()} once
   * <li>if the previous advance call returned {@code true} sequential reading starts and
   * {@code advance()} will be called repeatedly
   * </ol>
   * {@code advance()} calls {@code readNextRecord()} on the subclass, and stops (returns false) if
   * the new record is at a split point AND the offset of the new record is at or after B.
   *
   * <h2>Thread Safety</h2>
   *
   * <p> Since this class implements {@link Source.Reader} it guarantees thread safety. Abstract
   * methods defined here will not be accessed by more than one thread concurrently.
   */
  public abstract static class FileBasedReader<T> extends ByteOffsetBasedReader<T> {
    private ReadableByteChannel channel = null;
    private boolean finished = false; // Reader has finished advancing.
    private boolean endPositionReached = false; // If true, records have been read up to the ending
                                                // offset but the last split point may not have been
                                                // reached.
    private boolean startCalled = false;

    /**
     * Subclasses should not perform IO operations at the constructor. All IO operations should be
     * delayed until the {@link #startReading} method is invoked.
     */
    public FileBasedReader(FileBasedSource<T> source) {
      super(source);
      Preconditions.checkArgument(source.getMode() != Mode.FILEPATTERN,
          "FileBasedReader does not support reading file patterns");
    }

    @Override
    public FileBasedSource<T> getCurrentSource() {
      return (FileBasedSource<T>) super.getCurrentSource();
    }

    @Override
    public final boolean start() throws IOException {
      FileBasedSource<T> source = getCurrentSource();
      Preconditions.checkState(!startCalled, "start() should only be called once");
      IOChannelFactory factory = IOChannelUtils.getFactory(source.getFileOrPatternSpec());
      this.channel = factory.open(source.getFileOrPatternSpec());

      if (channel instanceof SeekableByteChannel) {
        SeekableByteChannel seekChannel = (SeekableByteChannel) channel;
        seekChannel.position(source.getStartOffset());
      } else {
        // Channel is not seekable. Must not be a subrange.
        Preconditions.checkArgument(source.mode != Mode.SINGLE_FILE_OR_SUBRANGE,
            "Subrange-based sources must only be defined for file types that support seekable "
            + " read channels");
        Preconditions.checkArgument(source.getStartOffset() == 0, "Start offset "
            + source.getStartOffset()
            + " is not zero but channel for reading the file is not seekable.");
      }

      startReading(channel);
      startCalled = true;

      // Advance once to load the first record.
      return advance();
    }

    @Override
    public final boolean advance() throws IOException {
      FileBasedSource<T> source = getCurrentSource();
      Preconditions.checkState(startCalled, "advance() called before calling start()");
      if (finished) {
        return false;
      }

      if (!readNextRecord()) {
        // End of the stream reached.
        finished = true;
        return false;
      }
      if (getCurrentOffset() >= source.getEndOffset()) {
        // Current record is at or after the end position defined by the source. The reader should
        // continue reading until the next split point is reached.
        endPositionReached = true;
      }

      // If the current record is at or after the end position defined by the source and if the
      // current record is at a split point, then the current record, and any record after that
      // does not belong to the offset range of the source.
      if (endPositionReached && isAtSplitPoint()) {
        finished = true;
        return false;
      }

      return true;
    }

    /**
     * Closes any {@link ReadableByteChannel} created for the current reader. This implementation is
     * idempotent. Any {@code close()} method introduced by a subclass must be idempotent and must
     * call the {@code close()} method in the {@code FileBasedReader}.
     */
    @Override
    public void close() throws IOException {
      if (channel != null) {
        channel.close();
      }
    }

    /**
     * Specifies if the current record of the reader is at a split point.
     *
     * <p>This returns {@code true} if the last record returned by {@link #readNextRecord} is at a
     * split point, {@code false} otherwise. Please refer to {@link FileBasedSource.FileBasedReader
     * FileBasedReader} for the definition of split points.
     */
    protected abstract boolean isAtSplitPoint();

    /**
     * Performs any initialization of the subclass of {@code FileBasedReader} that involves IO
     * operations. Will only be invoked once and before that invocation the base class will seek the
     * channel to the source's starting offset.
     *
     * <p>Provided {@link ReadableByteChannel} is for the file represented by the source of this
     * reader. Subclass may use the {@code channel} to build a higher level IO abstraction, e.g., a
     * BufferedReader or an XML parser.
     *
     * <p>A subclass may additionally use this to adjust the starting position prior to reading
     * records. For example, the channel of a reader that reads text lines may point to the middle
     * of a line after the position adjustment done at {@code FileBasedReader}. In this case the
     * subclass could adjust the position of the channel to the beginning of the next line. If the
     * corresponding source is for a subrange of a file, {@code channel} is guaranteed to be an
     * instance of the type {@link SeekableByteChannel} in which case the subclass may traverse back
     * in the channel to determine if the channel is already at the correct starting position (e.g.,
     * to check if the previous character was a newline).
     *
     * <p>After this method is invoked the base class will not be reading data from the channel or
     * adjusting the position of the channel. But the base class is responsible for properly closing
     * the channel.
     *
     * @param channel a byte channel representing the file backing the reader.
     */
    protected abstract void startReading(ReadableByteChannel channel) throws IOException;

    /**
     * Reads the next record from the channel provided by {@link #startReading}. Methods
     * {@link #getCurrent}, {@link #getCurrentOffset}, and {@link #isAtSplitPoint()} should return
     * the corresponding information about the record read by the last invocation of this method.
     *
     * @return {@code true} if a record was successfully read, {@code false} if the end of the
     *         channel was reached before successfully reading a new record.
     */
    protected abstract boolean readNextRecord() throws IOException;
  }

  // An internal Reader implementation that concatenates a sequence of FileBasedReaders.
  private class FilePatternReader extends AbstractBoundedReader<T> {
    private final FileBasedSource<T> source;
    private final List<FileBasedReader<T>> fileReaders;
    final ListIterator<FileBasedReader<T>> fileReadersIterator;
    FileBasedReader<T> currentReader = null;

    public FilePatternReader(FileBasedSource<T> source, List<FileBasedReader<T>> fileReaders) {
      this.source = source;
      this.fileReaders = fileReaders;
      this.fileReadersIterator = fileReaders.listIterator();
    }

    @Override
    public boolean start() throws IOException {
      return startNextNonemptyReader();
    }

    @Override
    public boolean advance() throws IOException {
      Preconditions.checkState(currentReader != null, "Call start() before advance()");
      if (currentReader.advance()) {
        return true;
      }
      return startNextNonemptyReader();
    }

    private boolean startNextNonemptyReader() throws IOException {
      while (fileReadersIterator.hasNext()) {
        currentReader = fileReadersIterator.next();
        if (currentReader.start()) {
          return true;
        }
        currentReader.close();
      }
      return false;
    }

    @Override
    public T getCurrent() throws NoSuchElementException {
      // A NoSuchElement will be thrown by the last FileBasedReader if getCurrent() is called after
      // advance() returns false.
      return currentReader.getCurrent();
    }

    @Override
    public Instant getCurrentTimestamp() throws NoSuchElementException {
      // A NoSuchElement will be thrown by the last FileBasedReader if getCurrentTimestamp()
      // is called after advance() returns false.
      return currentReader.getCurrentTimestamp();
    }

    @Override
    public void close() throws IOException {
      // Close all readers that may have not yet been closed.
      currentReader.close();
      while (fileReadersIterator.hasNext()) {
        fileReadersIterator.next().close();
      }
    }

    @Override
    public FileBasedSource<T> getCurrentSource() {
      return source;
    }

    @Override
    public FileBasedSource<T> splitAtFraction(double fraction) {
      // Unsupported. TODO: implement.
      LOG.debug("Dynamic splitting of FilePatternReader is unsupported.");
      return null;
    }

    @Override
    public Double getFractionConsumed() {
      if (currentReader == null) {
        return 0.0;
      }
      if (fileReaders.isEmpty()) {
        return 1.0;
      }
      int index = fileReadersIterator.previousIndex();
      int numReaders = fileReaders.size();
      if (index == numReaders) {
        return 1.0;
      }
      double before = 1.0 * index / numReaders;
      double after = 1.0 * (index + 1) / numReaders;
      Double fractionOfCurrentReader = currentReader.getFractionConsumed();
      if (fractionOfCurrentReader == null) {
        return before;
      }
      return before + fractionOfCurrentReader * (after - before);
    }
  }
}
