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

package com.google.cloud.dataflow.sdk.runners.worker;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.util.CoderUtils;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.util.common.worker.BatchingShuffleEntryReader;
import com.google.cloud.dataflow.sdk.util.common.worker.NativeReader;
import com.google.cloud.dataflow.sdk.util.common.worker.ShuffleEntry;
import com.google.cloud.dataflow.sdk.util.common.worker.ShuffleEntryReader;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.util.Iterator;

import javax.annotation.Nullable;

/**
 * A source that reads from a shuffled dataset, without any key grouping.
 * Returns just the values.  (This reader is for an UNGROUPED shuffle session.)
 *
 * @param <T> the type of the elements read from the source
 */
public class UngroupedShuffleReader<T> extends NativeReader<T> {
  final byte[] shuffleReaderConfig;
  final String startShufflePosition;
  final String stopShufflePosition;
  final Coder<T> coder;
  final CounterSet.AddCounterMutator addCounterMutator;

  public UngroupedShuffleReader(
      @SuppressWarnings("unused") PipelineOptions options, byte[] shuffleReaderConfig,
      @Nullable String startShufflePosition, @Nullable String stopShufflePosition, Coder<T> coder,
      @Nullable CounterSet.AddCounterMutator addCounterMutator) {
    this.shuffleReaderConfig = shuffleReaderConfig;
    this.startShufflePosition = startShufflePosition;
    this.stopShufflePosition = stopShufflePosition;
    this.coder = coder;
    this.addCounterMutator = addCounterMutator;
  }

  @Override
  public NativeReaderIterator<T> iterator() throws IOException {
    Preconditions.checkArgument(shuffleReaderConfig != null);
    return iterator(new BatchingShuffleEntryReader(
        new ChunkingShuffleBatchReader(
            new ApplianceShuffleReader(
                shuffleReaderConfig,
                addCounterMutator))));
  }

  UngroupedShuffleReaderIterator iterator(ShuffleEntryReader reader) {
    return new UngroupedShuffleReaderIterator(reader);
  }

  /**
   * A ReaderIterator that reads from a ShuffleEntryReader and extracts
   * just the values.
   */
  class UngroupedShuffleReaderIterator extends LegacyReaderIterator<T> {
    Iterator<ShuffleEntry> iterator;

    UngroupedShuffleReaderIterator(ShuffleEntryReader reader) {
      this.iterator = reader.read(
          ByteArrayShufflePosition.fromBase64(startShufflePosition),
          ByteArrayShufflePosition.fromBase64(stopShufflePosition));
    }

    @Override
    protected boolean hasNextImpl() throws IOException {
      return iterator.hasNext();
    }

    @Override
    protected T nextImpl() throws IOException {
      ShuffleEntry record = iterator.next();
      // Throw away the primary and the secondary keys.
      byte[] value = record.getValue();
      notifyElementRead(record.length());
      return CoderUtils.decodeFromByteArray(coder, value);
    }
  }
}
