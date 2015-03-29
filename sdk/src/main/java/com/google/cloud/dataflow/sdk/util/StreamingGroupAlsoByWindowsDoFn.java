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

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.transforms.Combine.KeyedCombineFn;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.PartitioningWindowFn;
import com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn;
import com.google.cloud.dataflow.sdk.util.TriggerExecutor.TimerManager;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.common.base.Preconditions;

import org.joda.time.Instant;

/**
 * DoFn that merges windows and groups elements in those windows.
 *
 * @param <K> key type
 * @param <VI> input value element type
 * @param <VO> output value element type
 * @param <W> window type
 */
@SuppressWarnings("serial")
public abstract class StreamingGroupAlsoByWindowsDoFn<K, VI, VO, W extends BoundedWindow>
    extends DoFn<TimerOrElement<KV<K, VI>>, KV<K, VO>> implements DoFn.RequiresKeyedState {

  public static <K, VI, VO, W extends BoundedWindow>
      StreamingGroupAlsoByWindowsDoFn<K, VI, VO, W> create(
          final WindowFn<?, W> windowFn,
          final KeyedCombineFn<K, VI, ?, VO> combineFn,
          final Coder<K> keyCoder,
          final Coder<VI> inputValueCoder) {
    Preconditions.checkNotNull(combineFn);
    return new StreamingGABWViaWindowSetDoFn<K, VI, VO, W>(windowFn) {
      @Override
      AbstractWindowSet<K, VI, VO, W> createWindowSet(K key,
          DoFn<TimerOrElement<KV<K, VI>>, KV<K, VO>>.ProcessContext context)
          throws Exception {
        return new CombiningWindowSet<>(
            key, windowFn, combineFn, keyCoder, inputValueCoder, context);
      }
    };
  }

  public static <K, VI, W extends BoundedWindow>
  StreamingGroupAlsoByWindowsDoFn<K, VI, Iterable<VI>, W>
  createForIterable(final WindowFn<?, W> windowFn, final Coder<VI> inputValueCoder) {
    return new StreamingGABWViaWindowSetDoFn<K, VI, Iterable<VI>, W>(windowFn) {
      @Override
      AbstractWindowSet<K, VI, Iterable<VI>, W> createWindowSet(K key,
          DoFn<TimerOrElement<KV<K, VI>>, KV<K, Iterable<VI>>>.ProcessContext context)
          throws Exception {
        if (windowFn instanceof PartitioningWindowFn) {
          return new PartitionBufferingWindowSet<K, VI, W>(
            key, windowFn, inputValueCoder, context);
        } else {
          return new BufferingWindowSet<K, VI, W>(
              key, windowFn, inputValueCoder, context);
        }
      }
    };
  }

  private abstract static class StreamingGABWViaWindowSetDoFn<K, VI, VO, W extends BoundedWindow>
  extends StreamingGroupAlsoByWindowsDoFn<K, VI, VO, W> {
    private final WindowFn<Object, W> windowFn;

    public StreamingGABWViaWindowSetDoFn(WindowFn<?, W> windowFn) {
      @SuppressWarnings("unchecked")
      WindowFn<Object, W> noWildcard = (WindowFn<Object, W>) windowFn;
      this.windowFn = noWildcard;
    }

    abstract AbstractWindowSet<K, VI, VO, W> createWindowSet(
        K key,
        DoFn<TimerOrElement<KV<K, VI>>, KV<K, VO>>.ProcessContext context)
        throws Exception;

    @Override
    public void processElement(ProcessContext context) throws Exception {
      if (!context.element().isTimer()) {
        KV<K, VI> element = context.element().element();
        K key = element.getKey();
        VI value = element.getValue();
        AbstractWindowSet<K, VI, VO, W> windowSet = createWindowSet(key, context);
        TriggerExecutor<K, VI, VO, W> executor = new TriggerExecutor<>(
            windowFn,
            new StreamingTimerManager(context),
            new DefaultTrigger<W>(),
            context.windowingInternals(),
            windowSet);

        executor.onElement(value, context.windowingInternals().windows());
        windowSet.persist();
      } else {
        TimerOrElement<KV<K, VI>> timer = context.element();
        @SuppressWarnings("unchecked")
        K key = (K) timer.key();
        AbstractWindowSet<K, VI, VO, W> windowSet = createWindowSet(key, context);
        TriggerExecutor<K, VI, VO, W> executor = new TriggerExecutor<>(
            windowFn,
            new StreamingTimerManager(context),
            new DefaultTrigger<W>(),
            context.windowingInternals(),
            windowSet);

        executor.onTimer(timer.tag());
        windowSet.persist();
      }
    }
  }

  private static class StreamingTimerManager implements TimerManager {

    private DoFn<?, ?>.ProcessContext context;

    public StreamingTimerManager(DoFn<?, ?>.ProcessContext context) {
      this.context = context;
    }

    @Override
    public void setTimer(String timer, Instant timestamp) {
      context.windowingInternals().setTimer(timer, timestamp);
    }

    @Override
    public void deleteTimer(String timer) {
      context.windowingInternals().deleteTimer(timer);
    }
  }
}
