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

import static com.google.api.client.util.Preconditions.checkNotNull;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.cloudPositionToReaderPosition;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.cloudProgressToReaderProgress;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.splitRequestToApproximateSplitRequest;
import static com.google.cloud.dataflow.sdk.util.common.Counter.AggregationKind.SUM;

import com.google.api.services.dataflow.model.ApproximateReportedProgress;
import com.google.api.services.dataflow.model.ApproximateSplitRequest;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.IterableCoder;
import com.google.cloud.dataflow.sdk.coders.KvCoder;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.util.BatchModeExecutionContext;
import com.google.cloud.dataflow.sdk.util.CoderUtils;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.util.WindowedValue.WindowedValueCoder;
import com.google.cloud.dataflow.sdk.util.common.Counter;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.util.common.ElementByteSizeObservableIterable;
import com.google.cloud.dataflow.sdk.util.common.ElementByteSizeObservableIterator;
import com.google.cloud.dataflow.sdk.util.common.Reiterable;
import com.google.cloud.dataflow.sdk.util.common.Reiterator;
import com.google.cloud.dataflow.sdk.util.common.worker.AbstractBoundedReaderIterator;
import com.google.cloud.dataflow.sdk.util.common.worker.BatchingShuffleEntryReader;
import com.google.cloud.dataflow.sdk.util.common.worker.GroupingShuffleEntryIterator;
import com.google.cloud.dataflow.sdk.util.common.worker.KeyGroupedShuffleEntries;
import com.google.cloud.dataflow.sdk.util.common.worker.Reader;
import com.google.cloud.dataflow.sdk.util.common.worker.ShuffleEntry;
import com.google.cloud.dataflow.sdk.util.common.worker.ShuffleEntryReader;
import com.google.cloud.dataflow.sdk.util.common.worker.StateSampler;
import com.google.cloud.dataflow.sdk.util.common.worker.StateSampler.StateKind;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.common.base.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

/**
 * A source that reads from a shuffled dataset and yields key-grouped data.
 *
 * @param <K> the type of the keys read from the shuffle
 * @param <V> the type of the values read from the shuffle
 */
public class GroupingShuffleReader<K, V> extends Reader<WindowedValue<KV<K, Reiterable<V>>>> {
  private static final Logger LOG = LoggerFactory.getLogger(GroupingShuffleReader.class);
  public static final String SOURCE_NAME = "GroupingShuffleSource";

  final byte[] shuffleReaderConfig;
  @Nullable final String startShufflePosition;
  @Nullable final String stopShufflePosition;
  final BatchModeExecutionContext executionContext;
  @Nullable final CounterSet.AddCounterMutator addCounterMutator;
  @Nullable final String operationName;

  // Counts how many bytes were from by a given operation from a given shuffle session.
  @Nullable Counter<Long> perOperationPerDatasetBytesCounter;
  Coder<K> keyCoder;
  Coder<V> valueCoder;

  public GroupingShuffleReader(
      PipelineOptions options,
      byte[] shuffleReaderConfig,
      @Nullable String startShufflePosition,
      @Nullable String stopShufflePosition,
      Coder<WindowedValue<KV<K, Iterable<V>>>> coder,
      BatchModeExecutionContext executionContext,
      CounterSet.AddCounterMutator addCounterMutator,
      String operationName)
      throws Exception {
    this.shuffleReaderConfig = shuffleReaderConfig;
    this.startShufflePosition = startShufflePosition;
    this.stopShufflePosition = stopShufflePosition;
    this.executionContext = executionContext;
    this.addCounterMutator = addCounterMutator;
    this.operationName = operationName;
    initCoder(coder);
    // We cannot initialize perOperationPerDatasetBytesCounter here, as it
    // depends on shuffleReaderConfig, which isn't populated yet.
  }

  private synchronized void initCounter(String datasetId) {
    if (perOperationPerDatasetBytesCounter == null
        && addCounterMutator != null
        && operationName != null) {
      perOperationPerDatasetBytesCounter =
          addCounterMutator.addCounter(
              Counter.longs(
                  "dax-shuffle-" + datasetId + "-wf-" + operationName + "-read-bytes",
                  SUM));
    }
  }

  @Override
  protected StateKind getStateSamplerStateKind() {
    return StateKind.FRAMEWORK;
  }

  @Override
  public ReaderIterator<WindowedValue<KV<K, Reiterable<V>>>> iterator() throws IOException {
    Preconditions.checkArgument(shuffleReaderConfig != null);
    ApplianceShuffleReader asr = new ApplianceShuffleReader(shuffleReaderConfig);
    String datasetId = asr.getDatasetId();
    initCounter(datasetId);

    return iterator(new BatchingShuffleEntryReader(
        new ChunkingShuffleBatchReader(asr)));
  }

  private void initCoder(Coder<WindowedValue<KV<K, Iterable<V>>>> coder) throws Exception {
    if (!(coder instanceof WindowedValueCoder)) {
      throw new Exception("unexpected kind of coder for WindowedValue: " + coder);
    }
    Coder<KV<K, Iterable<V>>> elemCoder =
        ((WindowedValueCoder<KV<K, Iterable<V>>>) coder).getValueCoder();
    if (!(elemCoder instanceof KvCoder)) {
      throw new Exception("unexpected kind of coder for elements read from "
          + "a key-grouping shuffle: " + elemCoder);
    }

    @SuppressWarnings("unchecked")
    KvCoder<K, Iterable<V>> kvCoder = (KvCoder<K, Iterable<V>>) elemCoder;
    this.keyCoder = kvCoder.getKeyCoder();
    Coder<Iterable<V>> kvValueCoder = kvCoder.getValueCoder();
    if (!(kvValueCoder instanceof IterableCoder)) {
      throw new Exception("unexpected kind of coder for values of KVs read from "
          + "a key-grouping shuffle");
    }
    IterableCoder<V> iterCoder = (IterableCoder<V>) kvValueCoder;
    this.valueCoder = iterCoder.getElemCoder();
  }

  final ReaderIterator<WindowedValue<KV<K, Reiterable<V>>>> iterator(ShuffleEntryReader reader) {
    return new GroupingShuffleReaderIterator(reader);
  }

  /**
   * A ReaderIterator that reads from a ShuffleEntryReader and groups
   * all the values with the same key.
   *
   * <p>A key limitation of this implementation is that all iterator accesses
   * must by externally synchronized (the iterator objects are not individually
   * thread-safe, and the iterators derived from a single original iterator
   * access shared state that is not thread-safe).
   *
   * <p>To access the current position, the iterator must advance
   * on-demand and cache the next batch of key grouped shuffle
   * entries. The iterator does not advance a second time in @next()
   * to avoid asking the underlying iterator to advance to the next
   * key before the caller/user iterates over the values corresponding
   * to the current key, which would introduce a performance
   * penalty.
   */
  final class GroupingShuffleReaderIterator
      extends AbstractBoundedReaderIterator<WindowedValue<KV<K, Reiterable<V>>>> {
    // N.B. This class is *not* static; it uses the keyCoder, valueCoder, and
    // executionContext from its enclosing GroupingShuffleReader.

    /** The iterator over shuffle entries, grouped by common key. */
    private final Iterator<KeyGroupedShuffleEntries> groups;

    private final GroupingShuffleRangeTracker rangeTracker;
    private ByteArrayShufflePosition lastGroupStart;

    /** The next group to be consumed, if available. */
    private KeyGroupedShuffleEntries currentGroup = null;
    private final AtomicLong currentGroupSize = new AtomicLong(0L);

    protected StateSampler stateSampler = null;
    protected int readState;

    public GroupingShuffleReaderIterator(ShuffleEntryReader reader) {
      if (GroupingShuffleReader.this.stateSampler == null) {
        // This code path is only used in tests.
        CounterSet counterSet = new CounterSet();
        this.stateSampler = new StateSampler("local", counterSet.getAddCounterMutator());
        this.readState = stateSampler.stateForName("shuffle", StateSampler.StateKind.FRAMEWORK);
      } else {
        checkNotNull(GroupingShuffleReader.this.stateSamplerOperationName);
        this.stateSampler = GroupingShuffleReader.this.stateSampler;
        this.readState = stateSampler.stateForName(
            GroupingShuffleReader.this.stateSamplerOperationName + "-process",
            StateSampler.StateKind.FRAMEWORK);
      }

      this.rangeTracker =
          new GroupingShuffleRangeTracker(
              ByteArrayShufflePosition.fromBase64(startShufflePosition),
              ByteArrayShufflePosition.fromBase64(stopShufflePosition));
      try (StateSampler.ScopedState read = stateSampler.scopedState(readState)) {
        this.groups =
            new GroupingShuffleEntryIterator(
                reader.read(rangeTracker.getStartPosition(), rangeTracker.getStopPosition()),
                GroupingShuffleReader.this.perOperationPerDatasetBytesCounter) {
              @Override
              protected void notifyElementRead(long byteSize) {
                // We accumulate the sum of bytes read in a local variable. This sum will be counted
                // when the values are actually read by the consumer of the shuffle reader.
                currentGroupSize.addAndGet(byteSize);
                GroupingShuffleReader.this.notifyElementRead(byteSize);
              }
            };
      }
    }

    @Override
    protected boolean hasNextImpl() throws IOException {
      try (StateSampler.ScopedState read = stateSampler.scopedState(readState)) {
        if (!groups.hasNext()) {
          return false;
        }
        currentGroup = groups.next();
      }
      ByteArrayShufflePosition groupStart = ByteArrayShufflePosition.of(currentGroup.position);
      boolean isAtSplitPoint = (lastGroupStart == null) || (!groupStart.equals(lastGroupStart));
      lastGroupStart = groupStart;
      return rangeTracker.tryReturnRecordAt(isAtSplitPoint, groupStart);
    }

    @Override
    protected WindowedValue<KV<K, Reiterable<V>>> nextImpl() throws IOException {
      K key = CoderUtils.decodeFromByteArray(keyCoder, currentGroup.key);
      if (executionContext != null) {
        executionContext.setKey(key);
      }

      KeyGroupedShuffleEntries group = currentGroup;
      currentGroup = null;
      return WindowedValue.valueInEmptyWindows(
          KV.<K, Reiterable<V>>of(key, new ValuesIterable(group.values)));
    }

    @Override
    public double getRemainingParallelism() {
      // Return 1 iff the stop position <= the lexicographic successor to the current position.
      ByteArrayShufflePosition stopPosition = rangeTracker.getStopPosition();
      if (stopPosition != null
          && lastGroupStart != null
          && stopPosition.compareTo(lastGroupStart.immediateSuccessor()) <= 0) {
        return 1;
      } else {
        return Double.POSITIVE_INFINITY;
      }
    }

    /**
     * Returns the position before the next {@code KV<K, Reiterable<V>>} to be returned by the
     * {@link GroupingShuffleReaderIterator}. Returns null if the
     * {@link GroupingShuffleReaderIterator} is finished.
     */
    @Override
    public Progress getProgress() {
      com.google.api.services.dataflow.model.Position position =
          new com.google.api.services.dataflow.model.Position();
      ApproximateReportedProgress progress = new ApproximateReportedProgress();
      ByteArrayShufflePosition groupStart = rangeTracker.getLastGroupStart();
      if (groupStart != null) {
        position.setShufflePosition(groupStart.encodeBase64());
        progress.setPosition(position);
      }
      return cloudProgressToReaderProgress(progress);
    }

    /**
     * Updates the stop position of the shuffle source to the position proposed. Ignores the
     * proposed stop position if it is smaller than or equal to the position before the next
     * {@code KV<K, Reiterable<V>>} to be returned by the {@link GroupingShuffleReaderIterator}.
     */
    @Override
    public DynamicSplitResult requestDynamicSplit(DynamicSplitRequest splitRequest) {
      checkNotNull(splitRequest);
      ApproximateSplitRequest splitProgress = splitRequestToApproximateSplitRequest(
          splitRequest);
      com.google.api.services.dataflow.model.Position splitPosition = splitProgress.getPosition();
      if (splitPosition == null) {
        LOG.warn("GroupingShuffleReader only supports split at a Position. Requested: {}",
            splitRequest);
        return null;
      }
      String splitShufflePosition = splitPosition.getShufflePosition();
      if (splitShufflePosition == null) {
        LOG.warn("GroupingShuffleReader only supports split at a shuffle position. Requested: {}",
            splitPosition);
        return null;
      }
      ByteArrayShufflePosition newStopPosition =
          ByteArrayShufflePosition.fromBase64(splitShufflePosition);
      if (rangeTracker.trySplitAtPosition(newStopPosition)) {
        LOG.info(
            "Split GroupingShuffleReader at {}, now {}",
            newStopPosition.encodeBase64(),
            rangeTracker);
        return new DynamicSplitResultWithPosition(cloudPositionToReaderPosition(splitPosition));
      } else {
        LOG.info(
            "Refused to split GroupingShuffleReader {} at {}",
            rangeTracker,
            newStopPosition.encodeBase64());
        return null;
      }
    }

    /**
     * Provides the {@link Reiterable} used to iterate through the values part
     * of a {@code KV<K, Reiterable<V>>} entry produced by a
     * {@link GroupingShuffleReader}.
     */
    private final class ValuesIterable extends ElementByteSizeObservableIterable<V, ValuesIterator>
        implements Reiterable<V> {
      // N.B. This class is *not* static; it uses the valueCoder from
      // its enclosing GroupingShuffleReader.

      private final Reiterable<ShuffleEntry> base;

      public ValuesIterable(Reiterable<ShuffleEntry> base) {
        this.base = checkNotNull(base);
      }

      @Override
      public ValuesIterator iterator() {
        return new ValuesIterator(base.iterator());
      }

      @Override
      protected ValuesIterator createIterator() {
        return iterator();
      }
    }

    /**
     * Provides the {@link Reiterator} used to iterate through the values part
     * of a {@code KV<K, Reiterable<V>>} entry produced by a
     * {@link GroupingShuffleReader}.
     */
    private final class ValuesIterator extends ElementByteSizeObservableIterator<V>
        implements Reiterator<V> {
      // N.B. This class is *not* static; it uses the valueCoder from
      // its enclosing GroupingShuffleReader.

      private final Reiterator<ShuffleEntry> base;

      public ValuesIterator(Reiterator<ShuffleEntry> base) {
        this.base = checkNotNull(base);
      }

      @Override
      public boolean hasNext() {
        try (StateSampler.ScopedState read =
            GroupingShuffleReaderIterator.this.stateSampler.scopedState(
                GroupingShuffleReaderIterator.this.readState)) {
          return base.hasNext();
        }
      }

      @Override
      public V next() {
        try (StateSampler.ScopedState read =
            GroupingShuffleReaderIterator.this.stateSampler.scopedState(
                GroupingShuffleReaderIterator.this.readState)) {
          ShuffleEntry entry = base.next();

          // The shuffle entries are handed over to the consumer of this iterator. Therefore, we can
          // notify the bytes that have been read so far.
          notifyValueReturned(currentGroupSize.getAndSet(0L));
          try {
            return CoderUtils.decodeFromByteArray(valueCoder, entry.getValue());
          } catch (IOException exn) {
            throw new RuntimeException(exn);
          }
        }
      }

      @Override
      public void remove() {
        base.remove();
      }

      @Override
      public ValuesIterator copy() {
        return new ValuesIterator(base.copy());
      }
    }
  }
}
