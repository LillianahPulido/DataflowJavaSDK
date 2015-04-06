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

import com.google.api.client.util.Preconditions;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.Combine;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.DoFn.KeyedState;
import com.google.cloud.dataflow.sdk.transforms.DoFn.RequiresKeyedState;
import com.google.cloud.dataflow.sdk.transforms.DoFn.RequiresWindowAccess;
import com.google.cloud.dataflow.sdk.transforms.DoFn.WindowingInternals;
import com.google.cloud.dataflow.sdk.transforms.SerializableFunction;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn;
import com.google.cloud.dataflow.sdk.util.ExecutionContext.StepContext;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.values.CodedTupleTag;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;

import org.joda.time.Instant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Runs a DoFn by constructing the appropriate contexts and passing them in.
 *
 * @param <I> the type of the DoFn's (main) input elements
 * @param <O> the type of the DoFn's (main) output elements
 * @param <R> the type of object which receives outputs
 */
public class DoFnRunner<I, O, R> {

  /** Information about how to create output receivers and output to them. */
  public interface OutputManager<R> {

    /** Returns the receiver to use for a given tag. */
    public R initialize(TupleTag<?> tag);

    /** Outputs a single element to the provided receiver. */
    public void output(R receiver, WindowedValue<?> output);

  }

  /** The DoFn being run. */
  public final DoFn<I, O> fn;

  /** The context used for running the DoFn. */
  public final DoFnContext<I, O, R> context;

  private DoFnRunner(PipelineOptions options,
                     DoFn<I, O> fn,
                     PTuple sideInputs,
                     OutputManager<R> outputManager,
                     TupleTag<O> mainOutputTag,
                     List<TupleTag<?>> sideOutputTags,
                     StepContext stepContext,
                     CounterSet.AddCounterMutator addCounterMutator,
                     WindowFn windowFn) {
    this.fn = fn;
    this.context = new DoFnContext<>(options, fn, sideInputs, outputManager,
                                     mainOutputTag, sideOutputTags, stepContext,
                                     addCounterMutator, windowFn);
  }

  public static <I, O, R> DoFnRunner<I, O, R> create(
      PipelineOptions options,
      DoFn<I, O> fn,
      PTuple sideInputs,
      OutputManager<R> outputManager,
      TupleTag<O> mainOutputTag,
      List<TupleTag<?>> sideOutputTags,
      StepContext stepContext,
      CounterSet.AddCounterMutator addCounterMutator,
      WindowFn windowFn) {
    return new DoFnRunner<>(
        options, fn, sideInputs, outputManager,
        mainOutputTag, sideOutputTags, stepContext, addCounterMutator, windowFn);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static <I, O> DoFnRunner<I, O, List> createWithListOutputs(
      PipelineOptions options,
      DoFn<I, O> fn,
      PTuple sideInputs,
      TupleTag<O> mainOutputTag,
      List<TupleTag<?>> sideOutputTags,
      StepContext stepContext,
      CounterSet.AddCounterMutator addCounterMutator,
      WindowFn windowFn) {
    return create(
        options, fn, sideInputs,
        new OutputManager<List>() {
          @Override
          public List initialize(TupleTag<?> tag) {
            return new ArrayList<>();
          }
          @Override
          public void output(List list, WindowedValue<?> output) {
            list.add(output);
          }
        },
        mainOutputTag, sideOutputTags, stepContext, addCounterMutator, windowFn);
  }

  /** Calls {@link DoFn#startBundle}. */
  public void startBundle() {
    // This can contain user code. Wrap it in case it throws an exception.
    try {
      fn.startBundle(context);
    } catch (Throwable t) {
      // Exception in user code.
      Throwables.propagateIfInstanceOf(t, UserCodeException.class);
      throw new UserCodeException(t);
    }
  }

  /**
   * Calls {@link DoFn#processElement} with a ProcessContext containing
   * the current element.
   */
  public void processElement(WindowedValue<I> elem) {
    if (elem.getWindows().size() == 1
        || !RequiresWindowAccess.class.isAssignableFrom(fn.getClass())) {
      invokeProcessElement(elem);
    } else {
      // We could modify the windowed value (and the processContext) to
      // avoid repeated allocations, but this is more straightforward.
      for (BoundedWindow window : elem.getWindows()) {
        invokeProcessElement(WindowedValue.of(
            elem.getValue(), elem.getTimestamp(), window));
      }
    }
  }

  private void invokeProcessElement(WindowedValue<I> elem) {
    DoFnProcessContext<I, O> processContext = new DoFnProcessContext<I, O>(fn, context, elem);
    // This can contain user code. Wrap it in case it throws an exception.
    try {
      fn.processElement(processContext);
    } catch (Throwable t) {
      // Exception in user code.
      Throwables.propagateIfInstanceOf(t, UserCodeException.class);
      throw new UserCodeException(t);
    }
  }

  /** Calls {@link DoFn#finishBundle}. */
  public void finishBundle() {
    // This can contain user code. Wrap it in case it throws an exception.
    try {
      fn.finishBundle(context);
    } catch (Throwable t) {
      // Exception in user code.
      Throwables.propagateIfInstanceOf(t, UserCodeException.class);
      throw new UserCodeException(t);
    }
  }

  /** Returns the receiver who gets outputs with the provided tag. */
  public R getReceiver(TupleTag<?> tag) {
    return context.getReceiver(tag);
  }

  /**
   * A concrete implementation of {@link DoFn<I, O>.Context} used for running
   * a {@link DoFn}.
   *
   * @param <I> the type of the DoFn's (main) input elements
   * @param <O> the type of the DoFn's (main) output elements
   * @param <R> the type of object which receives outputs
   */
  private static class DoFnContext<I, O, R> extends DoFn<I, O>.Context {
    private static final int MAX_SIDE_OUTPUTS = 1000;

    final PipelineOptions options;
    final DoFn<I, O> fn;
    final PTuple sideInputs;
    final Map<TupleTag<?>, Map<BoundedWindow, Object>> sideInputCache;
    final OutputManager<R> outputManager;
    final Map<TupleTag<?>, R> outputMap;
    final TupleTag<O> mainOutputTag;
    final StepContext stepContext;
    final CounterSet.AddCounterMutator addCounterMutator;
    final WindowFn windowFn;

    public DoFnContext(PipelineOptions options,
                       DoFn<I, O> fn,
                       PTuple sideInputs,
                       OutputManager<R> outputManager,
                       TupleTag<O> mainOutputTag,
                       List<TupleTag<?>> sideOutputTags,
                       StepContext stepContext,
                       CounterSet.AddCounterMutator addCounterMutator,
                       WindowFn windowFn) {
      fn.super();
      this.options = options;
      this.fn = fn;
      this.sideInputs = sideInputs;
      this.sideInputCache = new HashMap<>();
      this.outputManager = outputManager;
      this.mainOutputTag = mainOutputTag;
      this.outputMap = new HashMap<>();
      outputMap.put(mainOutputTag, outputManager.initialize(mainOutputTag));
      for (TupleTag<?> sideOutputTag : sideOutputTags) {
        outputMap.put(sideOutputTag, outputManager.initialize(sideOutputTag));
      }
      this.stepContext = stepContext;
      this.addCounterMutator = addCounterMutator;
      this.windowFn = windowFn;
    }

    public R getReceiver(TupleTag<?> tag) {
      R receiver = outputMap.get(tag);
      if (receiver == null) {
        throw new IllegalArgumentException(
            "calling getReceiver() with unknown tag " + tag);
      }
      return receiver;
    }

    //////////////////////////////////////////////////////////////////////////////

    @Override
    public PipelineOptions getPipelineOptions() {
      return options;
    }

    @SuppressWarnings("unchecked")
    <T> T sideInput(PCollectionView<T> view, BoundedWindow mainInputWindow) {
      TupleTag<?> tag = view.getTagInternal();
      Map<BoundedWindow, Object> tagCache = sideInputCache.get(tag);
      if (tagCache == null) {
        if (!sideInputs.has(tag)) {
          throw new IllegalArgumentException(
              "calling sideInput() with unknown view; did you forget to pass the view in "
              + "ParDo.withSideInputs()?");
        }
        tagCache = new HashMap<>();
        sideInputCache.put(tag, tagCache);
      }

      final BoundedWindow sideInputWindow =
          view.getWindowFnInternal().getSideInputWindow(mainInputWindow);

      T result = (T) tagCache.get(sideInputWindow);

      // TODO: Consider partial prefetching like in CoGBK to reduce iteration cost.
      if (result == null) {
        if (windowFn instanceof GlobalWindows) {
          result = view.fromIterableInternal((Iterable<WindowedValue<?>>) sideInputs.get(tag));
        } else {
          result = view.fromIterableInternal(Iterables.filter(
              (Iterable<WindowedValue<?>>) sideInputs.get(tag),
              new Predicate<WindowedValue<?>>() {
                @Override
                public boolean apply(WindowedValue<?> element) {
                  return element.getWindows().contains(sideInputWindow);
                }
              }));
        }
        tagCache.put(sideInputWindow, result);
      }

      return result;
    }

    <T> WindowedValue<T> makeWindowedValue(
        T output, Instant timestamp, Collection<? extends BoundedWindow> windows) {
      final Instant inputTimestamp = timestamp;

      if (timestamp == null) {
        timestamp = BoundedWindow.TIMESTAMP_MIN_VALUE;
      }

      if (windows == null) {
        try {
          windows = windowFn.assignWindows(windowFn.new AssignContext() {
              @Override
              public Object element() {
                throw new UnsupportedOperationException(
                    "WindowFn attemped to access input element when none was available");
              }

              @Override
              public Instant timestamp() {
                if (inputTimestamp == null) {
                  throw new UnsupportedOperationException(
                      "WindowFn attemped to access input timestamp when none was available");
                }
                return inputTimestamp;
              }

              @Override
              public Collection<? extends BoundedWindow> windows() {
                throw new UnsupportedOperationException(
                    "WindowFn attemped to access input windows when none were available");
              }
            });
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }

      return WindowedValue.of(output, timestamp, windows);
    }

    void outputWindowedValue(
        O output,
        Instant timestamp,
        Collection<? extends BoundedWindow> windows) {
      WindowedValue<O> windowedElem = makeWindowedValue(output, timestamp, windows);
      outputManager.output(outputMap.get(mainOutputTag), windowedElem);
      if (stepContext != null) {
        stepContext.noteOutput(windowedElem);
      }
    }

    protected <T> void sideOutputWindowedValue(TupleTag<T> tag,
                                               T output,
                                               Instant timestamp,
                                               Collection<? extends BoundedWindow> windows) {
      R receiver = outputMap.get(tag);
      if (receiver == null) {
        // This tag wasn't declared nor was it seen before during this execution.
        // Thus, this must be a new, undeclared and unconsumed output.

        // To prevent likely user errors, enforce the limit on the number of side
        // outputs.
        if (outputMap.size() >= MAX_SIDE_OUTPUTS) {
          throw new IllegalArgumentException(
              "the number of side outputs has exceeded a limit of "
              + MAX_SIDE_OUTPUTS);
        }

        // Register the new TupleTag with outputManager and add an entry for it in
        // the outputMap.
        receiver = outputManager.initialize(tag);
        outputMap.put(tag, receiver);
      }

      WindowedValue<T> windowedElem = makeWindowedValue(output, timestamp, windows);
      outputManager.output(receiver, windowedElem);
      if (stepContext != null) {
        stepContext.noteSideOutput(tag, windowedElem);
      }
    }

    // Following implementations of output, outputWithTimestamp, and sideOutput
    // are only accessible in DoFn.startBundle and DoFn.finishBundle, and will be shadowed by
    // ProcessContext's versions in DoFn.processElement.
    @Override
    public void output(O output) {
      outputWindowedValue(output, null, null);
    }

    @Override
    public void outputWithTimestamp(O output, Instant timestamp) {
      outputWindowedValue(output, timestamp, null);
    }

    @Override
    public <T> void sideOutput(TupleTag<T> tag, T output) {
      sideOutputWindowedValue(tag, output, null, null);
    }

    @Override
    public <T> void sideOutputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
      sideOutputWindowedValue(tag, output, timestamp, null);
    }

    private String generateInternalAggregatorName(String userName) {
      return "user-" + stepContext.getStepName() + "-" + userName;
    }

    @Override
    public <AI, AA, AO> Aggregator<AI> createAggregator(
        String name, Combine.CombineFn<? super AI, AA, AO> combiner) {
      return new AggregatorImpl<>(
          generateInternalAggregatorName(name), combiner, addCounterMutator);
    }

    @Override
    public <AI, AO> Aggregator<AI> createAggregator(
        String name, SerializableFunction<Iterable<AI>, AO> combiner) {
      return new AggregatorImpl<AI, Iterable<AI>, AO>(
          generateInternalAggregatorName(name), combiner, addCounterMutator);
    }
  }

  /**
   * A concrete implementation of {@link DoFn<I, O>.ProcessContext} used for running
   * a {@link DoFn} over a single element.
   *
   * @param <I> the type of the DoFn's (main) input elements
   * @param <O> the type of the DoFn's (main) output elements
   */
  private static class DoFnProcessContext<I, O> extends DoFn<I, O>.ProcessContext {


    final DoFn<I, O> fn;
    final DoFnContext<I, O, ?> context;
    final WindowedValue<I> windowedValue;

    public DoFnProcessContext(DoFn<I, O> fn,
                              DoFnContext<I, O, ?> context,
                              WindowedValue<I> windowedValue) {
      fn.super();
      this.fn = fn;
      this.context = context;
      this.windowedValue = windowedValue;
    }

    @Override
    public PipelineOptions getPipelineOptions() {
      return context.getPipelineOptions();
    }

    @Override
    public I element() {
      return windowedValue.getValue();
    }

    @Override
    public <T> T sideInput(PCollectionView<T> view) {
      Iterator<? extends BoundedWindow> windowIter = windows().iterator();
      BoundedWindow window;
      if (!windowIter.hasNext()) {
        if (context.windowFn instanceof GlobalWindows) {
          // TODO: Remove this once GroupByKeyOnly no longer outputs elements
          // without windows
          window = GlobalWindow.INSTANCE;
        } else {
          throw new IllegalStateException(
              "sideInput called when main input element is not in any windows");
        }
      } else {
        window = windowIter.next();
        if (windowIter.hasNext()) {
          throw new IllegalStateException(
              "sideInput called when main input element is in multiple windows");
        }
      }
      return context.sideInput(view, window);
    }

    @Override
    public KeyedState keyedState() {
      if (!(fn instanceof RequiresKeyedState)
          || !equivalentToKV(element())) {
        throw new UnsupportedOperationException(
            "Keyed state is only available in the context of a keyed DoFn "
            + "marked as requiring state");
      }

      return context.stepContext;
    }


    @Override
    public BoundedWindow window() {
      if (!(fn instanceof RequiresWindowAccess)) {
        throw new UnsupportedOperationException(
            "window() is only available in the context of a DoFn marked as RequiresWindow.");
      }
      return Iterables.getOnlyElement(windows());
    }

    @Override
    public void output(O output) {
      context.outputWindowedValue(output, windowedValue.getTimestamp(), windowedValue.getWindows());
    }

    @Override
    public void outputWithTimestamp(O output, Instant timestamp) {
      checkTimestamp(timestamp);
      context.outputWindowedValue(output, timestamp, windowedValue.getWindows());
    }

    void outputWindowedValue(
        O output,
        Instant timestamp,
        Collection<? extends BoundedWindow> windows) {
      context.outputWindowedValue(output, timestamp, windows);
    }

    @Override
    public <T> void sideOutput(TupleTag<T> tag, T output) {
      context.sideOutputWindowedValue(tag,
                                      output,
                                      windowedValue.getTimestamp(),
                                      windowedValue.getWindows());
    }

    @Override
    public <T> void sideOutputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
      checkTimestamp(timestamp);
      context.sideOutputWindowedValue(tag, output, timestamp, windowedValue.getWindows());
    }

    @Override
    public <AI, AA, AO> Aggregator<AI> createAggregator(
        String name, Combine.CombineFn<? super AI, AA, AO> combiner) {
      return context.createAggregator(name, combiner);
    }

    @Override
    public <AI, AO> Aggregator<AI> createAggregator(
        String name, SerializableFunction<Iterable<AI>, AO> combiner) {
      return context.createAggregator(name, combiner);
    }

    @Override
    public Instant timestamp() {
      return windowedValue.getTimestamp();
    }

    public Collection<? extends BoundedWindow> windows() {
      return windowedValue.getWindows();
    }

    private void checkTimestamp(Instant timestamp) {
      Preconditions.checkArgument(
          !timestamp.isBefore(windowedValue.getTimestamp().minus(fn.getAllowedTimestampSkew())));
    }

    private boolean equivalentToKV(I input) {
      if (input == null) {
        return true;
      } else if (input instanceof KV) {
        return true;
      } else if (input instanceof TimerOrElement) {
        return ((TimerOrElement) input).isTimer()
            || ((TimerOrElement) input).element() instanceof KV;
      }
      return false;
    }

    @Override
    public WindowingInternals<I, O> windowingInternals() {
      return new WindowingInternals<I, O>() {
        @Override
        public void outputWindowedValue(O output, Instant timestamp,
            Collection<? extends BoundedWindow> windows) {
          context.outputWindowedValue(output, timestamp, windows);
        }

        @Override
        public <T> void writeToTagList(CodedTupleTag<T> tag, T value, Instant timestamp)
            throws IOException {
          context.stepContext.writeToTagList(tag, value, timestamp);
        }

        @Override
        public <T> void deleteTagList(CodedTupleTag<T> tag) {
          context.stepContext.deleteTagList(tag);
        }

        @Override
        public <T> Iterable<T> readTagList(CodedTupleTag<T> tag) throws IOException {
          return context.stepContext.readTagList(tag);
        }

        @Override
        public void setTimer(String timer, Instant timestamp) {
          context.stepContext.getExecutionContext().setTimer(timer, timestamp);
        }

        @Override
        public void deleteTimer(String timer) {
          context.stepContext.getExecutionContext().deleteTimer(timer);
        }

        @Override
        public Collection<? extends BoundedWindow> windows() {
          return windowedValue.getWindows();
        }
      };
    }
}
}
