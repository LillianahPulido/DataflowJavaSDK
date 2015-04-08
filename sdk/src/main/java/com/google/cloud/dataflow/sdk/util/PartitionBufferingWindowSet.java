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

package com.google.cloud.dataflow.sdk.util;

import static com.google.cloud.dataflow.sdk.util.WindowUtils.bufferTag;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.transforms.DoFn.KeyedState;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger.WindowStatus;
import com.google.cloud.dataflow.sdk.values.CodedTupleTag;
import com.google.cloud.dataflow.sdk.values.TimestampedValue;
import com.google.common.base.Function;
import com.google.common.collect.Iterables;

import org.joda.time.Instant;

import java.util.Collection;

/**
 * A WindowSet where each value is placed in exactly one window,
 * and windows are never merged, deleted, or flushed early, and the
 * WindowSet itself is never exposed to user code, allowing
 * a much simpler (and cheaper) implementation.
 *
 * <p>This WindowSet only works with {@link StreamingGroupAlsoByWindowsDoFn}.
 */
class PartitionBufferingWindowSet<K, V, W extends BoundedWindow>
    extends AbstractWindowSet<K, V, Iterable<V>, W> {

  public static <K, V, W extends BoundedWindow>
  AbstractWindowSet.Factory<K, V, Iterable<V>, W> factory(final Coder<V> inputCoder) {
    return new AbstractWindowSet.Factory<K, V, Iterable<V>, W>() {

      private static final long serialVersionUID = 0L;

      @Override
      public AbstractWindowSet<K, V, Iterable<V>, W> create(K key,
          Coder<W> windowFn, KeyedState keyedState,
          WindowingInternals<?, ?> windowingInternals) throws Exception {
        return new PartitionBufferingWindowSet<>(
            key, windowFn, inputCoder, keyedState, windowingInternals);
      }
    };
  }

  private PartitionBufferingWindowSet(
      K key,
      Coder<W> windowCoder,
      Coder<V> inputCoder,
      KeyedState keyedState,
      WindowingInternals<?, ?> windowingInternals) {
    super(key, windowCoder, inputCoder, keyedState, windowingInternals);
  }

  @Override
  public WindowStatus put(W window, V value, Instant timestamp) throws Exception {
    windowingInternals.writeToTagList(
        bufferTag(window, windowCoder, inputCoder), value, timestamp);

    // Adds the window even if it is already present, relying on the streaming backend to
    // de-duplicate. As such, we don't know if this was a genuinely new window.
    return WindowStatus.UNKNOWN;
  }

  @Override
  public void remove(W window) throws Exception {
    windowingInternals.deleteTagList(bufferTag(window, windowCoder, inputCoder));
  }

  @Override
  public void merge(Collection<W> otherWindows, W newWindow) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<W> windows() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean contains(W window) {
    throw new UnsupportedOperationException();
  }

  @Override
  protected TimestampedValue<Iterable<V>> finalValue(W window) throws Exception {
    CodedTupleTag<V> tag = bufferTag(window, windowCoder, inputCoder);
    Iterable<TimestampedValue<V>> result = windowingInternals.readTagList(tag);
    if (result == null) {
      return null;
    }

    Instant timestamp = result.iterator().next().getTimestamp();
    return TimestampedValue.of(
        Iterables.transform(result, new Function<TimestampedValue<V>, V>() {
              @Override
              public V apply(TimestampedValue<V> input) {
                return input.getValue();
              }
            }),
        timestamp);
  }
}
