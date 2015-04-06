/*
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
 */

package com.google.cloud.dataflow.sdk.io;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.util.ExecutionContext;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

/**
 * Base class for defining input formats, with custom logic for splitting the input
 * into bundles (parts of the input, each of which may be processed on a different worker)
 * and creating a {@code Source} for reading the input.
 *
 * <p> To use this class for supporting your custom input type, derive your class
 * class from it, and override the abstract methods. For an example, see {@link DatastoreIO}.
 *
 * <p> A {@code Source} passed to a {@code Read} transform must be
 * {@code Serializable}.  This allows the {@code Source} instance
 * created in this "main program" to be sent (in serialized form) to
 * remote worker machines and reconstituted for each batch of elements
 * of the input {@code PCollection} being processed or for each source splitting
 * operation. A {@code Source} can have instance variable state, and
 * non-transient instance variable state will be serialized in the main program
 * and then deserialized on remote worker machines.
 *
 * <p> {@code Source} objects should implement {@link Object#toString}, as it will be
 * used in important error and debugging messages.
 *
 * <p> This API is experimental and subject to change.
 *
 * @param <T> Type of elements read by the source.
 */
public abstract class Source<T> implements Serializable {
  private static final long serialVersionUID = 0;

  /**
   * Splits the source into bundles.
   *
   * <p> {@code PipelineOptions} can be used to get information such as
   * credentials for accessing an external storage.
   */
  public abstract List<? extends Source<T>> splitIntoBundles(
      long desiredBundleSizeBytes, PipelineOptions options) throws Exception;

  /**
   * An estimate of the total size (in bytes) of the data that would be read from this source.
   * This estimate is in terms of external storage size, before any decompression or other
   * processing done by the reader.
   */
  public abstract long getEstimatedSizeBytes(PipelineOptions options) throws Exception;

  /**
   * Whether this source is known to produce key/value pairs with the (encoded) keys in
   * lexicographically sorted order.
   */
  public abstract boolean producesSortedKeys(PipelineOptions options) throws Exception;

  /**
   * Creates a reader for this source.
   */
  public Reader<T> createReader(
      PipelineOptions options, @Nullable ExecutionContext executionContext) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Checks that this source is valid, before it can be used in a pipeline.
   *
   * <p>It is recommended to use {@link com.google.common.base.Preconditions} for implementing
   * this method.
   */
  public abstract void validate();

  /**
   * Returns the default {@code Coder} to use for the data read from this source.
   */
  public abstract Coder<T> getDefaultOutputCoder();

  /**
   * The interface which readers of custom input sources must implement.
   * <p>
   * This interface is deliberately distinct from {@link java.util.Iterator} because
   * the current model tends to be easier to program and more efficient in practice
   * for iterating over sources such as files, databases etc. (rather than pure collections).
   * <p>
   * To read a {@code Reader}:
   * <pre>
   * for (boolean available = reader.start(); available; available = reader.advance()) {
   *   T item = reader.getCurrent();
   *   ...
   * }
   * </pre>
   * <p>
   * Note: this interface is work-in-progress and may change.
   */
  public interface Reader<T> extends AutoCloseable {

    /**
     * Initializes the reader and advances the reader to the first record.
     *
     * <p> This method should be called exactly once. The invocation should occur prior to calling
     * {@link #advance} or {@link #getCurrent}. This method may perform expensive operations that
     * are needed to initialize the reader.
     *
     * @return {@code true} if a record was read, {@code false} if we're at the end of input.
     */
    public boolean start() throws IOException;

    /**
     * Advances the iterator to the next valid record.
     * Invalidates the result of the previous {@link #getCurrent} call.
     * @return {@code true} if a record was read, {@code false} if we're at the end of input.
     */
    public boolean advance() throws IOException;

    /**
     * Returns the value of the data item which was read by the last {@link #start} or
     * {@link #advance} call.
     *
     * @throws java.util.NoSuchElementException if the iterator is at the beginning of the input and
     *         {@link #start} or {@link #advance} wasn't called, or if the last {@link #start} or
     *         {@link #advance} returned {@code false}.
     */
    public T getCurrent() throws NoSuchElementException;

    /**
     * Closes the iterator. The iterator cannot be used after this method was called.
     */
    @Override
    public void close() throws IOException;
  }
}
