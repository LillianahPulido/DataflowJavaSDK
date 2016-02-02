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

import static com.google.api.client.util.Base64.encodeBase64URLSafeString;
import static com.google.cloud.dataflow.sdk.runners.worker.ReaderTestUtils.approximateSplitRequestAtPosition;
import static com.google.cloud.dataflow.sdk.runners.worker.ReaderTestUtils.positionFromSplitResult;
import static com.google.cloud.dataflow.sdk.runners.worker.ReaderTestUtils.splitRequestAtPosition;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.readerProgressToCloudProgress;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.toDynamicSplitRequest;
import static com.google.cloud.dataflow.sdk.util.common.Counter.AggregationKind.SUM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.api.services.dataflow.model.ApproximateReportedProgress;
import com.google.api.services.dataflow.model.Position;
import com.google.cloud.dataflow.sdk.coders.BigEndianIntegerCoder;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.Coder.Context;
import com.google.cloud.dataflow.sdk.coders.IterableCoder;
import com.google.cloud.dataflow.sdk.coders.KvCoder;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.runners.worker.GroupingShuffleReader.GroupingShuffleReaderIterator;
import com.google.cloud.dataflow.sdk.runners.worker.ShuffleSink.ShuffleKind;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.IntervalWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.PaneInfo;
import com.google.cloud.dataflow.sdk.util.BatchModeExecutionContext;
import com.google.cloud.dataflow.sdk.util.CoderUtils;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.util.common.Counter;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.util.common.ElementByteSizeObserver;
import com.google.cloud.dataflow.sdk.util.common.Reiterable;
import com.google.cloud.dataflow.sdk.util.common.worker.ExecutorTestUtils;
import com.google.cloud.dataflow.sdk.util.common.worker.NativeReader;
import com.google.cloud.dataflow.sdk.util.common.worker.ShuffleEntry;
import com.google.cloud.dataflow.sdk.util.common.worker.Sink;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.common.collect.Lists;

import org.joda.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

/**
 * Tests for GroupingShuffleReader.
 */
@RunWith(JUnit4.class)
public class GroupingShuffleReaderTest {
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  private static final List<KV<Integer, List<KV<Integer, Integer>>>> NO_KVS =
      Collections.emptyList();

  private static final Instant timestamp = new Instant(123000);
  private static final IntervalWindow window = new IntervalWindow(timestamp, timestamp.plus(1000));

  // As Shuffle records, {@code KV} is encoded as 10 records. Each records uses an integer as key
  // (4 bytes), and a {@code KV} of an integer key and value (each 4 bytes).
  // Overall {@code KV}s have a byte size of 25 * 4 = 100. Note that we also encode the
  // timestamp into the secondary key adding another 100 bytes.
  private static final List<KV<Integer, List<KV<Integer, Integer>>>> KVS = Arrays.asList(
      KV.of(1, Arrays.asList(KV.of(1, 11), KV.of(2, 12))),
      KV.of(2, Arrays.asList(KV.of(1, 21), KV.of(2, 22))),
      KV.of(3, Arrays.asList(KV.of(1, 31))),
      KV.of(4, Arrays.asList(KV.of(1, 41), KV.of(2, 42),
                             KV.of(3, 43), KV.of(4, 44))),
      KV.of(5, Arrays.asList(KV.of(1, 51))));

  /** How many of the values with each key are to be read.
   * Note that the order matters as the conversion to ordinal is used below.
   */
  private enum ValuesToRead {
    /** Don't even ask for the values iterator. */
    SKIP_VALUES,
    /** Get the iterator, but don't read any values. */
    READ_NO_VALUES,
    /** Read just the first value. */
    READ_ONE_VALUE,
    /** Read all the values. */
    READ_ALL_VALUES,
    /** Read all the values twice. */
    READ_ALL_VALUES_TWICE
  }

  private List<ShuffleEntry> writeShuffleEntries(
      List<KV<Integer, List<KV<Integer, Integer>>>> input, boolean sortValues)
      throws Exception {
    Coder<WindowedValue<KV<Integer, KV<Integer, Integer>>>> sinkElemCoder =
        WindowedValue.getFullCoder(
            KvCoder.of(BigEndianIntegerCoder.of(),
                       KvCoder.of(BigEndianIntegerCoder.of(),
                                  BigEndianIntegerCoder.of())),
            IntervalWindow.getCoder());
    CounterSet.AddCounterMutator addCounterMutator = new CounterSet().getAddCounterMutator();
    // Write to shuffle with GROUP_KEYS ShuffleSink.
    ShuffleSink<KV<Integer, KV<Integer, Integer>>> shuffleSink =
        new ShuffleSink<>(PipelineOptionsFactory.create(), null,
            sortValues ? ShuffleKind.GROUP_KEYS_AND_SORT_VALUES : ShuffleKind.GROUP_KEYS,
            sinkElemCoder, addCounterMutator);

    TestShuffleWriter shuffleWriter = new TestShuffleWriter();

    int kvCount = 0;
    List<Long> actualSizes = new ArrayList<>();
    try (Sink.SinkWriter<WindowedValue<KV<Integer, KV<Integer, Integer>>>> shuffleSinkWriter =
        shuffleSink.writer(shuffleWriter, "dataset")) {
      for (KV<Integer, List<KV<Integer, Integer>>> kvs : input) {
        Integer key = kvs.getKey();
        for (KV<Integer, Integer> value : kvs.getValue()) {
          ++kvCount;
          actualSizes.add(shuffleSinkWriter.add(WindowedValue.of(
              KV.of(key, value), timestamp, Lists.newArrayList(window), PaneInfo.NO_FIRING)));
        }
      }
    }
    List<ShuffleEntry> records = shuffleWriter.getRecords();
    assertEquals(kvCount, records.size());
    assertEquals(shuffleWriter.getSizes(), actualSizes);
    return records;
  }

  private List<KV<Integer, List<KV<Integer, Integer>>>> runIterationOverGroupingShuffleReader(
      BatchModeExecutionContext context, TestShuffleReader shuffleReader,
      GroupingShuffleReader<Integer, KV<Integer, Integer>> groupingShuffleReader,
      Coder<WindowedValue<KV<Integer, Iterable<KV<Integer, Integer>>>>> coder,
      ValuesToRead valuesToRead) throws Exception {
    Counter<Long> elementByteSizeCounter = Counter.longs("element-byte-size-counter", SUM);
    ElementByteSizeObserver elementObserver = new ElementByteSizeObserver(elementByteSizeCounter);
    List<KV<Integer, List<KV<Integer, Integer>>>> actual = new ArrayList<>();
    try (GroupingShuffleReaderIterator<Integer, KV<Integer, Integer>> iter =
        groupingShuffleReader.iterator(shuffleReader)) {
      Iterable<KV<Integer, Integer>> prevValuesIterable = null;
      Iterator<KV<Integer, Integer>> prevValuesIterator = null;
      while (iter.hasNext()) {
        assertTrue(iter.hasNext());
        assertTrue(iter.hasNext());

        @SuppressWarnings({"rawtypes", "unchecked"})  // safe co-variant cast.
        WindowedValue<KV<Integer, Iterable<KV<Integer, Integer>>>> windowedValue =
            (WindowedValue) iter.next();
        // Verify that the byte size observer is lazy for every value the GroupingShuffleReader
        // produces.
        coder.registerByteSizeObserver(windowedValue, elementObserver, Context.OUTER);
        assertTrue(elementObserver.getIsLazy());

        // Verify value is in an empty windows.
        assertEquals(BoundedWindow.TIMESTAMP_MIN_VALUE, windowedValue.getTimestamp());
        assertEquals(0, windowedValue.getWindows().size());

        KV<Integer, Iterable<KV<Integer, Integer>>> elem = windowedValue.getValue();
        Integer key = elem.getKey();
        List<KV<Integer, Integer>> values = new ArrayList<>();
        if (valuesToRead.ordinal() > ValuesToRead.SKIP_VALUES.ordinal()) {
          if (prevValuesIterable != null) {
            prevValuesIterable.iterator(); // Verifies that this does not throw.
          }
          if (prevValuesIterator != null) {
            prevValuesIterator.hasNext(); // Verifies that this does not throw.
          }

          Iterable<KV<Integer, Integer>> valuesIterable = elem.getValue();
          Iterator<KV<Integer, Integer>> valuesIterator = valuesIterable.iterator();

          if (valuesToRead.ordinal() >= ValuesToRead.READ_ONE_VALUE.ordinal()) {
            while (valuesIterator.hasNext()) {
              assertTrue(valuesIterator.hasNext());
              assertTrue(valuesIterator.hasNext());
              assertEquals("BatchModeExecutionContext key", key, context.getKey());
              values.add(valuesIterator.next());
              if (valuesToRead == ValuesToRead.READ_ONE_VALUE) {
                break;
              }
            }
            if (valuesToRead.ordinal() >= ValuesToRead.READ_ALL_VALUES.ordinal()) {
              assertFalse(valuesIterator.hasNext());
              assertFalse(valuesIterator.hasNext());

              try {
                valuesIterator.next();
                fail("Expected NoSuchElementException");
              } catch (NoSuchElementException exn) {
                // As expected.
              }
              valuesIterable.iterator(); // Verifies that this does not throw.
            }
          }
          if (valuesToRead == ValuesToRead.READ_ALL_VALUES_TWICE) {
            // Create new iterator;
            valuesIterator = valuesIterable.iterator();

            while (valuesIterator.hasNext()) {
              assertTrue(valuesIterator.hasNext());
              assertTrue(valuesIterator.hasNext());
              assertEquals("BatchModeExecutionContext key", key, context.getKey());
              valuesIterator.next();
            }
            assertFalse(valuesIterator.hasNext());
            assertFalse(valuesIterator.hasNext());
            try {
              valuesIterator.next();
              fail("Expected NoSuchElementException");
            } catch (NoSuchElementException exn) {
              // As expected.
            }
          }

          prevValuesIterable = valuesIterable;
          prevValuesIterator = valuesIterator;
        }

        actual.add(KV.of(key, values));
      }
      assertFalse(iter.hasNext());
      assertFalse(iter.hasNext());
      try {
        iter.next();
        fail("Expected NoSuchElementException");
      } catch (NoSuchElementException exn) {
        // As expected.
      }
    }
    return actual;
  }

  private void runTestReadFromShuffle(
      List<KV<Integer, List<KV<Integer, Integer>>>> input, boolean sortValues,
      ValuesToRead valuesToRead) throws Exception {
    Coder<WindowedValue<KV<Integer, Iterable<KV<Integer, Integer>>>>> sourceElemCoder =
        WindowedValue.getFullCoder(
            KvCoder.of(BigEndianIntegerCoder.of(),
                       IterableCoder.of(KvCoder.of(BigEndianIntegerCoder.of(),
                                                   BigEndianIntegerCoder.of()))),
            IntervalWindow.getCoder());

    List<ShuffleEntry> records = writeShuffleEntries(input, sortValues);

    PipelineOptions options = PipelineOptionsFactory.create();
    // Read from shuffle with GroupingShuffleReader.
    BatchModeExecutionContext context = BatchModeExecutionContext.fromOptions(options);
    GroupingShuffleReader<Integer, KV<Integer, Integer>> groupingShuffleReader =
        new GroupingShuffleReader<>(
            options, null, null, null, sourceElemCoder, context, null, null, sortValues);
    ExecutorTestUtils.TestReaderObserver observer =
        new ExecutorTestUtils.TestReaderObserver(groupingShuffleReader);

    TestShuffleReader shuffleReader = new TestShuffleReader();
    List<Integer> expectedSizes = new ArrayList<>();
    for (ShuffleEntry record : records) {
      expectedSizes.add(record.length());
      shuffleReader.addEntry(record);
    }

    List<KV<Integer, List<KV<Integer, Integer>>>> actual = runIterationOverGroupingShuffleReader(
        context, shuffleReader, groupingShuffleReader, sourceElemCoder, valuesToRead);

    List<KV<Integer, List<KV<Integer, Integer>>>> expected = new ArrayList<>();
    for (KV<Integer, List<KV<Integer, Integer>>> kvs : input) {
      Integer key = kvs.getKey();
      List<KV<Integer, Integer>> values = new ArrayList<>();
      if (valuesToRead.ordinal() >= ValuesToRead.READ_ONE_VALUE.ordinal()) {
        for (KV<Integer, Integer> value : kvs.getValue()) {
          values.add(value);
          if (valuesToRead == ValuesToRead.READ_ONE_VALUE) {
            break;
          }
        }
      }
      expected.add(KV.of(key, values));
    }
    assertEquals(expected, actual);
    assertEquals(expectedSizes, observer.getActualSizes());
  }

  @Test
  public void testReadEmptyShuffleData() throws Exception {
    runTestReadFromShuffle(NO_KVS, false /* do not sort values */, ValuesToRead.READ_ALL_VALUES);
    runTestReadFromShuffle(NO_KVS, true /* sort values */, ValuesToRead.READ_ALL_VALUES);
  }

  @Test
  public void testReadEmptyShuffleDataSkippingValues() throws Exception {
    runTestReadFromShuffle(NO_KVS, false /* do not sort values */, ValuesToRead.SKIP_VALUES);
    runTestReadFromShuffle(NO_KVS, true /* sort values */, ValuesToRead.SKIP_VALUES);
  }

  @Test
  public void testReadNonEmptyShuffleData() throws Exception {
    runTestReadFromShuffle(KVS, false /* do not sort values */, ValuesToRead.READ_ALL_VALUES);
    runTestReadFromShuffle(KVS, true /* sort values */, ValuesToRead.READ_ALL_VALUES);
  }

  @Test
  public void testReadNonEmptyShuffleDataTwice() throws Exception {
    runTestReadFromShuffle(KVS, false /* do not sort values */, ValuesToRead.READ_ALL_VALUES_TWICE);
    runTestReadFromShuffle(KVS, true /* sort values */, ValuesToRead.READ_ALL_VALUES_TWICE);
  }

  @Test
  public void testReadNonEmptyShuffleDataReadingOneValue() throws Exception {
    runTestReadFromShuffle(KVS, false /* do not sort values */, ValuesToRead.READ_ONE_VALUE);
    runTestReadFromShuffle(KVS, true /* sort values */, ValuesToRead.READ_ONE_VALUE);
  }

  @Test
  public void testReadNonEmptyShuffleDataReadingNoValues() throws Exception {
    runTestReadFromShuffle(KVS, false /* do not sort values */, ValuesToRead.READ_NO_VALUES);
    runTestReadFromShuffle(KVS, true /* sort values */, ValuesToRead.READ_NO_VALUES);
  }

  @Test
  public void testReadNonEmptyShuffleDataSkippingValues() throws Exception {
    runTestReadFromShuffle(KVS, false /* do not sort values */, ValuesToRead.SKIP_VALUES);
    runTestReadFromShuffle(KVS, true /* sort values */, ValuesToRead.SKIP_VALUES);
  }

  private void runTestBytesReadCounter(
      List<KV<Integer, List<KV<Integer, Integer>>>> input, boolean useSecondaryKey,
      ValuesToRead valuesToRead, long expectedReadBytes) throws Exception {
    // Create a shuffle reader with the shuffle values provided as input.
    List<ShuffleEntry> records = writeShuffleEntries(input, useSecondaryKey);
    TestShuffleReader shuffleReader = new TestShuffleReader();
    for (ShuffleEntry record : records) {
      shuffleReader.addEntry(record);
    }

    Coder<WindowedValue<KV<Integer, Iterable<KV<Integer, Integer>>>>> sourceElemCoder =
        WindowedValue.getFullCoder(
            KvCoder.of(BigEndianIntegerCoder.of(),
                IterableCoder.of(KvCoder.of(BigEndianIntegerCoder.of(),
                                            BigEndianIntegerCoder.of()))),
            IntervalWindow.getCoder());
    PipelineOptions options = PipelineOptionsFactory.create();
    CounterSet.AddCounterMutator addCounterMutator =
        new CounterSet().getAddCounterMutator();
    // Read from shuffle with GroupingShuffleReader.
    BatchModeExecutionContext context = BatchModeExecutionContext.fromOptions(options);
    GroupingShuffleReader<Integer, KV<Integer, Integer>> groupingShuffleReader =
        new GroupingShuffleReader<>(
            options, null, null, null, sourceElemCoder, context, null, null, useSecondaryKey);
    groupingShuffleReader.perOperationPerDatasetBytesCounter =
        addCounterMutator.addCounter(Counter.longs("dax-shuffle-test-wf-read-bytes", SUM));

    runIterationOverGroupingShuffleReader(
        context, shuffleReader, groupingShuffleReader, sourceElemCoder, valuesToRead);

    assertEquals(expectedReadBytes,
                 (long) groupingShuffleReader.perOperationPerDatasetBytesCounter.getAggregate());
  }

  @Test
  public void testBytesReadNonEmptyShuffleData() throws Exception {
    runTestBytesReadCounter(KVS, false /* do not sort values */,
        ValuesToRead.READ_ALL_VALUES, 200L);
    runTestBytesReadCounter(KVS, true /* sort values */, ValuesToRead.READ_ALL_VALUES, 200L);
  }

  @Test
  public void testBytesReadNonEmptyShuffleDataTwice() throws Exception {
    runTestBytesReadCounter(KVS, false /* do not sort values */,
        ValuesToRead.READ_ALL_VALUES_TWICE, 200L);
    runTestBytesReadCounter(KVS, true /* sort values */, ValuesToRead.READ_ALL_VALUES_TWICE, 200L);
  }

  @Test
  public void testBytesReadNonEmptyShuffleDataReadingOneValue() throws Exception {
    runTestBytesReadCounter(KVS, false /* do not sort values */, ValuesToRead.READ_ONE_VALUE, 200L);
    runTestBytesReadCounter(KVS, true /* sort values */, ValuesToRead.READ_ONE_VALUE, 200L);
  }

  @Test
  public void testBytesReadNonEmptyShuffleDataSkippingValues() throws Exception {
    runTestBytesReadCounter(KVS, false /* do not sort values */, ValuesToRead.SKIP_VALUES, 200L);
    runTestBytesReadCounter(KVS, true /* sort values */, ValuesToRead.SKIP_VALUES, 200L);
  }

  @Test
  public void testBytesReadEmptyShuffleData() throws Exception {
    runTestBytesReadCounter(NO_KVS, false /* do not sort values */,
        ValuesToRead.READ_ALL_VALUES, 0L);
    runTestBytesReadCounter(NO_KVS, true /* sort values */, ValuesToRead.READ_ALL_VALUES, 0L);
  }

  static byte[] fabricatePosition(int shard) throws Exception {
    return fabricatePosition(shard, (Integer) null);
  }

  static byte[] fabricatePosition(int shard, @Nullable byte[] key) throws Exception {
    return fabricatePosition(shard, key == null ? null : Arrays.hashCode(key));
  }

  static byte[] fabricatePosition(int shard, @Nullable Integer keyHash) throws Exception {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(os);
    dos.writeInt(shard);
    if (keyHash != null) {
      dos.writeInt(keyHash);
    }
    return os.toByteArray();
  }

  @Test
  public void testReadFromShuffleDataAndFailToSplit() throws Exception {
    PipelineOptions options = PipelineOptionsFactory.create();
    BatchModeExecutionContext context = BatchModeExecutionContext.fromOptions(options);
    final int kFirstShard = 0;

    TestShuffleReader shuffleReader = new TestShuffleReader();
    final int kNumRecords = 2;
    for (int i = 0; i < kNumRecords; ++i) {
      byte[] key = CoderUtils.encodeToByteArray(BigEndianIntegerCoder.of(), i);
      shuffleReader.addEntry(
          new ShuffleEntry(fabricatePosition(kFirstShard, key), key, EMPTY_BYTE_ARRAY, key));
    }

    // Note that TestShuffleReader start/end positions are in the
    // space of keys not the positions (TODO: should probably always
    // use positions instead).
    String stop = encodeBase64URLSafeString(fabricatePosition(kNumRecords));
    GroupingShuffleReader<Integer, Integer> groupingShuffleReader = new GroupingShuffleReader<>(
        options, null, null, stop,
        WindowedValue.getFullCoder(
            KvCoder.of(BigEndianIntegerCoder.of(), IterableCoder.of(BigEndianIntegerCoder.of())),
            IntervalWindow.getCoder()),
        context, null, null, false /* do not sort values */);

    try (GroupingShuffleReaderIterator<Integer, Integer> iter =
            groupingShuffleReader.iterator(shuffleReader)) {
      // Poke the iterator so we can test dynamic splitting.
      assertTrue(iter.hasNext());

      // Cannot split since the value provided is past the current stop position.
      assertNull(iter.requestDynamicSplit(splitRequestAtPosition(
          makeShufflePosition(kNumRecords + 1, null))));

      int i = 0;
      for (; iter.hasNext(); ++i) {
        iter.next().getValue(); // ignored
        if (i == 0) {
          // First record
          byte[] key = CoderUtils.encodeToByteArray(BigEndianIntegerCoder.of(), i);
          // Cannot split since the split position is identical with the position of the record
          // that was just returned.
          assertNull(
              iter.requestDynamicSplit(splitRequestAtPosition(
                  makeShufflePosition(kFirstShard, key))));

          // Cannot split since the requested split position comes before current position
          assertNull(
              iter.requestDynamicSplit(splitRequestAtPosition(
                  makeShufflePosition(kFirstShard, null))));
        }
      }
      assertEquals(kNumRecords, i);

      // Cannot split since all input was consumed.
      assertNull(
          iter.requestDynamicSplit(splitRequestAtPosition(makeShufflePosition(kFirstShard, null))));
    }
  }

  @Test
  public void testRemainingParallelism() throws Exception {
    PipelineOptions options = PipelineOptionsFactory.create();
    BatchModeExecutionContext context = BatchModeExecutionContext.fromOptions(options);
    final int kFirstShard = 0;

    TestShuffleReader shuffleReader = new TestShuffleReader();
    final int kNumRecords = 5;
    for (int i = 0; i < kNumRecords; ++i) {
      byte[] key = CoderUtils.encodeToByteArray(BigEndianIntegerCoder.of(), i);
      ShuffleEntry entry = new ShuffleEntry(
          fabricatePosition(kFirstShard, i), key, EMPTY_BYTE_ARRAY, key);
      shuffleReader.addEntry(entry);
    }

    GroupingShuffleReader<Integer, Integer> groupingShuffleReader =
        new GroupingShuffleReader<>(
            options,
            null,
            null,
            null,
            WindowedValue.getFullCoder(
                KvCoder.of(
                    BigEndianIntegerCoder.of(), IterableCoder.of(BigEndianIntegerCoder.of())),
                IntervalWindow.getCoder()),
            context,
            null,
            null,
            false /* do not sort values */);

    try (GroupingShuffleReaderIterator<Integer, Integer> iter =
            groupingShuffleReader.iterator(shuffleReader)) {

      assertEquals(Double.POSITIVE_INFINITY, iter.getRemainingParallelism(), 0);

      // The only way to set a stop *position* in tests is via a split. To do that,
      // we must call hasNext() first.
      assertTrue(iter.hasNext());
      assertNotNull(
          iter.requestDynamicSplit(
              splitRequestAtPosition(
                  makeShufflePosition(
                      ByteArrayShufflePosition.of(fabricatePosition(kFirstShard, 2))
                          .immediateSuccessor()
                          .getPosition()))));
      assertTrue(iter.hasNext());

      assertEquals(Double.POSITIVE_INFINITY, iter.getRemainingParallelism(), 0);
      iter.next();
      assertEquals(Double.POSITIVE_INFINITY, iter.getRemainingParallelism(), 0);
      assertTrue(iter.hasNext());
      assertEquals(Double.POSITIVE_INFINITY, iter.getRemainingParallelism(), 0);
      iter.next();
      assertEquals(Double.POSITIVE_INFINITY, iter.getRemainingParallelism(), 0);
      assertTrue(iter.hasNext());
      assertEquals(1, iter.getRemainingParallelism(), 0);
      iter.next();
      assertEquals(1, iter.getRemainingParallelism(), 0);
      assertFalse(iter.hasNext());
      assertEquals(1, iter.getRemainingParallelism(), 0);
    }
  }

  private Position makeShufflePosition(int shard, byte[] key) throws Exception {
    return new Position().setShufflePosition(
        encodeBase64URLSafeString(fabricatePosition(shard, key)));
  }

  private Position makeShufflePosition(byte[] position) throws Exception {
    return new Position().setShufflePosition(encodeBase64URLSafeString(position));
  }

  @Test
  public void testReadFromShuffleAndDynamicSplit() throws Exception {
    PipelineOptions options = PipelineOptionsFactory.create();
    BatchModeExecutionContext context = BatchModeExecutionContext.fromOptions(options);
    CounterSet.AddCounterMutator addCounterMutator =
        new CounterSet().getAddCounterMutator();
    GroupingShuffleReader<Integer, Integer> groupingShuffleReader = new GroupingShuffleReader<>(
        options, null, null, null,
        WindowedValue.getFullCoder(
            KvCoder.of(BigEndianIntegerCoder.of(), IterableCoder.of(BigEndianIntegerCoder.of())),
            IntervalWindow.getCoder()),
        context, null, null, false /* do not sort values */);
    groupingShuffleReader.perOperationPerDatasetBytesCounter =
          addCounterMutator.addCounter(Counter.longs("dax-shuffle-test-wf-read-bytes", SUM));

    TestShuffleReader shuffleReader = new TestShuffleReader();
    final int kNumRecords = 10;
    final int kFirstShard = 0;
    final int kSecondShard = 1;

    // Setting up two shards with kNumRecords each; keys are unique
    // (hence groups of values for the same key are singletons)
    // therefore each record comes with a unique position constructed.
    for (int i = 0; i < kNumRecords; ++i) {
      byte[] keyByte = CoderUtils.encodeToByteArray(BigEndianIntegerCoder.of(), i);
      ShuffleEntry entry = new ShuffleEntry(
          fabricatePosition(kFirstShard, keyByte), keyByte, EMPTY_BYTE_ARRAY, keyByte);
      shuffleReader.addEntry(entry);
    }

    for (int i = kNumRecords; i < 2 * kNumRecords; ++i) {
      byte[] keyByte = CoderUtils.encodeToByteArray(BigEndianIntegerCoder.of(), i);

      ShuffleEntry entry = new ShuffleEntry(
          fabricatePosition(kSecondShard, keyByte), keyByte, EMPTY_BYTE_ARRAY, keyByte);
      shuffleReader.addEntry(entry);
    }

    int i = 0;
    try (GroupingShuffleReaderIterator<Integer, Integer> iter =
            groupingShuffleReader.iterator(shuffleReader)) {
      // Poke the iterator so we can test dynamic splitting.
      assertTrue(iter.hasNext());

      assertNull(iter.requestDynamicSplit(splitRequestAtPosition(new Position())));

      // Split at the shard boundary
      NativeReader.DynamicSplitResult dynamicSplitResult =
          iter.requestDynamicSplit(splitRequestAtPosition(makeShufflePosition(kSecondShard, null)));
      assertNotNull(dynamicSplitResult);
      assertEquals(
          encodeBase64URLSafeString(fabricatePosition(kSecondShard)),
          positionFromSplitResult(dynamicSplitResult).getShufflePosition());

      while (iter.hasNext()) {
        // iter.hasNext() is supposed to be side-effect-free and give the same result if called
        // repeatedly. Test that this is indeed the case.
        assertTrue(iter.hasNext());
        assertTrue(iter.hasNext());

        KV<Integer, Reiterable<Integer>> elem = iter.next().getValue();
        int key = elem.getKey();
        assertEquals(key, i);

        Iterable<Integer> valuesIterable = elem.getValue();
        Iterator<Integer> valuesIterator = valuesIterable.iterator();

        int j = 0;
        while (valuesIterator.hasNext()) {
          assertTrue(valuesIterator.hasNext());
          assertTrue(valuesIterator.hasNext());

          int value = valuesIterator.next();
          assertEquals(value, i);
          ++j;
        }
        assertFalse(valuesIterator.hasNext());
        assertFalse(valuesIterator.hasNext());
        assertEquals(j, 1);
        ++i;
      }
      assertFalse(iter.hasNext());
    }
    assertEquals(i, kNumRecords);
    // There are 10 Shuffle records that each encode an integer key (4 bytes) and integer value (4
    // bytes). We therefore expect to read 80 bytes.
    assertEquals(
        80L, (long) groupingShuffleReader.perOperationPerDatasetBytesCounter.getAggregate());
  }

  @Test
  public void testGetApproximateProgress() throws Exception {
    // Store the positions of all KVs returned.
    List<byte[]> positionsList = new ArrayList<byte[]>();

    PipelineOptions options = PipelineOptionsFactory.create();
    BatchModeExecutionContext context = BatchModeExecutionContext.fromOptions(options);
    GroupingShuffleReader<Integer, Integer> groupingShuffleReader = new GroupingShuffleReader<>(
        options, null, null, null,
        WindowedValue.getFullCoder(
            KvCoder.of(BigEndianIntegerCoder.of(), IterableCoder.of(BigEndianIntegerCoder.of())),
            IntervalWindow.getCoder()),
        context, null, null, false /* do not sort values */);

    TestShuffleReader shuffleReader = new TestShuffleReader();
    final int kNumRecords = 10;

    for (int i = 0; i < kNumRecords; ++i) {
      byte[] position = fabricatePosition(i);
      byte[] keyByte = CoderUtils.encodeToByteArray(BigEndianIntegerCoder.of(), i);
      positionsList.add(position);
      ShuffleEntry entry = new ShuffleEntry(position, keyByte, EMPTY_BYTE_ARRAY, keyByte);
      shuffleReader.addEntry(entry);
    }

    try (GroupingShuffleReaderIterator<Integer, Integer> readerIterator =
            groupingShuffleReader.iterator(shuffleReader)) {
      Integer i = 0;
      while (readerIterator.hasNext()) {
        assertTrue(readerIterator.hasNext());
        ApproximateReportedProgress progress = readerProgressToCloudProgress(
            readerIterator.getProgress());
        assertNotNull(progress.getPosition().getShufflePosition());

        // Compare returned position with the expected position.
        assertEquals(
            ByteArrayShufflePosition.of(positionsList.get(i)).encodeBase64(),
            progress.getPosition().getShufflePosition());

        WindowedValue<KV<Integer, Reiterable<Integer>>> elem = readerIterator.next();
        assertEquals(i, elem.getValue().getKey());
        i++;
      }
      assertFalse(readerIterator.hasNext());

      // Cannot split since all input was consumed.
      Position proposedSplitPosition = new Position();
      String stop = encodeBase64URLSafeString(fabricatePosition(0));
      proposedSplitPosition.setShufflePosition(stop);
      assertNull(
          readerIterator.requestDynamicSplit(
              toDynamicSplitRequest(approximateSplitRequestAtPosition(proposedSplitPosition))));
    }
  }
}
