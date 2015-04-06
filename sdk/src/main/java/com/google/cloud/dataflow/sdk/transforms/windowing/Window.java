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

package com.google.cloud.dataflow.sdk.transforms.windowing;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.runners.DirectPipelineRunner;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.util.AssignWindowsDoFn;
import com.google.cloud.dataflow.sdk.util.DirectModeExecutionContext;
import com.google.cloud.dataflow.sdk.util.DoFnRunner;
import com.google.cloud.dataflow.sdk.util.PTuple;
import com.google.cloud.dataflow.sdk.util.StringUtils;
import com.google.cloud.dataflow.sdk.util.WindowingStrategy;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.TupleTag;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code Window} logically divides up or groups the elements of a
 * {@link PCollection} into finite windows according to a {@link WindowFn}.
 * The output of {@code Window} contains the same elements as input, but they
 * have been logically assigned to windows. The next
 * {@link com.google.cloud.dataflow.sdk.transforms.GroupByKey GroupByKeys},
 * including one within composite transforms, will group by the combination of
 * keys and windows.

 * <p> See {@link com.google.cloud.dataflow.sdk.transforms.GroupByKey}
 * for more information about how grouping with windows works.
 *
 * <p> Windowing a {@code PCollection} allows chunks of it to be processed
 * individually, before the entire {@code PCollection} is available.  This is
 * especially important for {@code PCollection}s with unbounded size,
 * since the full {@code PCollection} is
 * never available at once, since more data is continually arriving.
 * For {@code PCollection}s with a bounded size (aka. conventional batch mode),
 * by default, all data is implicitly in a single window, unless
 * {@code Window} is applied.
 *
 * <p> For example, a simple form of windowing divides up the data into
 * fixed-width time intervals, using {@link FixedWindows}.
 * The following example demonstrates how to use {@code Window} in a pipeline
 * that counts the number of occurrences of strings each minute:
 *
 * <pre> {@code
 * PCollection<String> items = ...;
 * PCollection<String> windowed_items = item.apply(
 *   Window.<String>into(FixedWindows.of(1, TimeUnit.MINUTES)));
 * PCollection<KV<String, Long>> windowed_counts = windowed_items.apply(
 *   Count.<String>perElement());
 * } </pre>
 *
 * <p> Let (data, timestamp) denote a data element along with its timestamp.
 * Then, if the input to this pipeline consists of
 * {("foo", 15s), ("bar", 30s), ("foo", 45s), ("foo", 1m30s)},
 * the output will be
 * {(KV("foo", 2), 1m), (KV("bar", 1), 1m), (KV("foo", 1), 2m)}
 *
 * <p> Several predefined {@link WindowFn}s are provided:
 * <ul>
 *  <li> {@link FixedWindows} partitions the timestamps into fixed-width intervals.
 *  <li> {@link SlidingWindows} places data into overlapping fixed-width intervals.
 *  <li> {@link Sessions} groups data into sessions where each item in a window
 *       is separated from the next by no more than a specified gap.
 * </ul>
 *
 * <p>Additionally, custom {@link WindowFn}s can be created, by creating new
 * subclasses of {@link WindowFn}.
 *
 * <p> {@link Window.Bound#triggering(Trigger)} allows specifying a trigger to control when
 * (in processing time) results for the given window can be produced. If unspecified, the default
 * behavior is to trigger first when the watermark passes the end of the window, and then trigger
 * again every time there is late arriving data. See {@link Trigger} for details on specifying other
 * triggers.
 */
public class Window {
  /**
   * Creates a {@code Window} {@code PTransform} with the given name.
   *
   * <p> See the discussion of Naming in
   * {@link com.google.cloud.dataflow.sdk.transforms.ParDo} for more explanation.
   *
   * <p> The resulting {@code PTransform} is incomplete, and its input/output
   * type is not yet bound.  Use {@link Window.Unbound#into} to specify the
   * {@link WindowFn} to use, which will also bind the input/output type of this
   * {@code PTransform}.
   */
  public static Unbound named(String name) {
    return new Unbound().named(name);
  }

  /**
   * Creates a {@code Window} {@code PTransform} that uses the given
   * {@link WindowFn} to window the data.
   *
   * <p> The resulting {@code PTransform}'s types have been bound, with both the
   * input and output being a {@code PCollection<T>}, inferred from the types of
   * the argument {@code WindowFn<T, B>}.  It is ready to be applied, or further
   * properties can be set on it first.
   */
  public static <T> Bound<T> into(WindowFn<? super T, ?> fn) {
    return new Unbound().into(fn);
  }

  /**
   * An incomplete {@code Window} transform, with unbound input/output type.
   *
   * <p> Before being applied, {@link Window.Unbound#into} must be
   * invoked to specify the {@link WindowFn} to invoke, which will also
   * bind the input/output type of this {@code PTransform}.
   */
  public static class Unbound {
    String name;

    Unbound() {}

    Unbound(String name) {
      this.name = name;
    }

    /**
     * Returns a new {@code Window} transform that's like this
     * transform but with the specified name.  Does not modify this
     * transform.  The resulting transform is still incomplete.
     *
     * <p> See the discussion of Naming in
     * {@link com.google.cloud.dataflow.sdk.transforms.ParDo} for more
     * explanation.
     */
    public Unbound named(String name) {
      return new Unbound(name);
    }

    /**
     * Returns a new {@code Window} {@code PTransform} that's like this
     * transform but that will use the given {@link WindowFn}, and that has
     * its input and output types bound.  Does not modify this transform.  The
     * resulting {@code PTransform} is sufficiently specified to be applied,
     * but more properties can still be specified.
     */
    public <T> Bound<T> into(WindowFn<? super T, ?> fn) {
      return new Bound<>(name, createWindowingStrategy(fn, DefaultTrigger.of()));
    }
  }

  private static <T, W extends BoundedWindow> WindowingStrategy<? super T, ?>
    createWindowingStrategy(WindowFn<? super T, ?> fn, Trigger<?> trigger) {
    @SuppressWarnings("unchecked")
    WindowFn<? super T, W> typedFn = (WindowFn<? super T, W>) fn;
    @SuppressWarnings("unchecked")
    Trigger<W> typedTrigger = (Trigger<W>) trigger;
    return WindowingStrategy.of(typedFn, typedTrigger);
  }

  /**
   * A {@code PTransform} that windows the elements of a {@code PCollection<T>},
   * into finite windows according to a user-specified {@code WindowFn<T, B>}.
   *
   * @param <T> The type of elements this {@code Window} is applied to
   */
  @SuppressWarnings("serial")
  public static class Bound<T> extends PTransform<PCollection<T>, PCollection<T>> {

    WindowingStrategy<? super T, ?> windowingStrategy;

    Bound(String name, WindowingStrategy<? super T, ?> windowingStrategy) {
      this.name = name;
      this.windowingStrategy = windowingStrategy;
    }

    /**
     * Returns a new {@code Window} {@code PTransform} that's like this
     * {@code PTransform} but with the specified name.  Does not
     * modify this {@code PTransform}.
     *
     * <p> See the discussion of Naming in
     * {@link com.google.cloud.dataflow.sdk.transforms.ParDo} for more
     * explanation.
     */
    public Bound<T> named(String name) {
      return new Bound<>(name, windowingStrategy);
    }

    /**
     * Sets a non-default trigger for this {@code Window} {@code PTransform}.
     * Elements that are assigned to a specific window will be output when
     * the trigger fires.
     *
     * <p> {@link com.google.cloud.dataflow.sdk.transforms.windowing.Trigger}
     * has more details on the available triggers.
     */
    public Triggering<T> triggering(Trigger<?> trigger) {
      return new Triggering<T>(name,
          createWindowingStrategy(windowingStrategy.getWindowFn(), trigger));
    }

    @Override
    public PCollection<T> apply(PCollection<T> input) {
      return PCollection.<T>createPrimitiveOutputInternal(windowingStrategy);
    }

    @Override
    protected Coder<?> getDefaultOutputCoder(PCollection<T> input) {
      return input.getCoder();
    }

    public WindowingStrategy<? super T, ?> getWindowingStrategy() {
      return windowingStrategy;
    }

    @Override
    protected String getKindString() {
      return "Window.Into("
          + StringUtils.approximateSimpleName(windowingStrategy.getWindowFn().getClass())
          + ", "
          // TODO: Add support for describing triggers.
          + StringUtils.approximateSimpleName(windowingStrategy.getTrigger().getClass())
          + ")";
    }
  }

  /**
   * An incomplete {@code Window} transform which has a trigger specified but has an unspecified
   * accumulation mode.
   *
   * <p> The currently available accumulation modes are:
   *
   * <ul>
   *   <li> {@link Window.Triggering#discardingFiredPanes} which causes the elements in a pane to
   *   be discarded after the trigger fires and output is produced.
   * </ul>
   *
   * <p> After specifying the accumulation mode the PTransform is complete and can be applied.
   */
  public static class Triggering<T> {

    String name;
    WindowingStrategy<? super T, ?> windowingStrategy;

    Triggering(String name, WindowingStrategy<? super T, ?> windowingStrategy) {
      this.name = name;
      this.windowingStrategy = windowingStrategy;
    }

    /**
     * Returns a new {@code Window} {@code PTransform} that uses the registered WindowFn and
     * Triggering behavior, and which discards elements in a pane after they are triggered.
     *
     * <p> Does not modify this transform.  The resulting {@code PTransform} is sufficiently
     * specified to be applied, but more properties can still be specified.
     */
    public Bound<T> discardingFiredPanes() {
      return new Bound<>(name, windowingStrategy);
    }
  }

  /////////////////////////////////////////////////////////////////////////////

  /**
   * Creates a {@code Window} {@code PTransform} that does not change assigned
   * windows, but will cause windows to be merged again as part of the next
   * {@link com.google.cloud.dataflow.sdk.transforms.GroupByKey}.
   */
  public static <T> Remerge<T> remerge() {
    return new Remerge<T>();
  }

  /**
   * {@code PTransform} that does not change assigned windows, but will cause
   *  windows to be merged again as part of the next
   * {@link com.google.cloud.dataflow.sdk.transforms.GroupByKey}.
   */
  @SuppressWarnings("serial")
  public static class Remerge<T> extends PTransform<PCollection<T>, PCollection<T>> {
    @Override
    public PCollection<T> apply(PCollection<T> input) {
      WindowingStrategy<?, ?> outputWindowingStrategy = getOutputWindowing(
          input.getWindowingStrategy());

      return input.apply(ParDo.named("Identity").of(new DoFn<T, T>() {
                @Override public void processElement(ProcessContext c) {
                  c.output(c.element());
                }
              })).setWindowingStrategyInternal(outputWindowingStrategy);
    }

    private <W extends BoundedWindow> WindowingStrategy<?, W> getOutputWindowing(
        WindowingStrategy<?, W> inputStrategy) {
      if (inputStrategy.getWindowFn() instanceof InvalidWindows) {
        @SuppressWarnings("unchecked")
        InvalidWindows<W> invalidWindows = (InvalidWindows<W>) inputStrategy.getWindowFn();
        return WindowingStrategy.of(
            invalidWindows.getOriginalWindowFn(), inputStrategy.getTrigger());
      } else {
        return inputStrategy;
      }
    }
  }

  /////////////////////////////////////////////////////////////////////////////

  static {
    DirectPipelineRunner.registerDefaultTransformEvaluator(
        Bound.class,
        new DirectPipelineRunner.TransformEvaluator<Bound>() {
          @Override
          public void evaluate(
              Bound transform,
              DirectPipelineRunner.EvaluationContext context) {
            evaluateHelper(transform, context);
          }
        });
  }

  private static <T> void evaluateHelper(
      Bound<T> transform,
      DirectPipelineRunner.EvaluationContext context) {
    PCollection<T> input = context.getInput(transform);

    DirectModeExecutionContext executionContext = new DirectModeExecutionContext();

    TupleTag<T> outputTag = new TupleTag<>();
    DoFn<T, T> addWindowsDoFn = new AssignWindowsDoFn<>(transform.windowingStrategy.getWindowFn());
    DoFnRunner<T, T, List> addWindowsRunner =
        DoFnRunner.createWithListOutputs(
            context.getPipelineOptions(),
            addWindowsDoFn,
            PTuple.empty(),
            outputTag,
            new ArrayList<TupleTag<?>>(),
            executionContext.getStepContext(context.getStepName(transform)),
            context.getAddCounterMutator(),
            transform.windowingStrategy);

    addWindowsRunner.startBundle();

    // Process input elements.
    for (DirectPipelineRunner.ValueWithMetadata<T> inputElem
             : context.getPCollectionValuesWithMetadata(input)) {
      executionContext.setKey(inputElem.getKey());
      addWindowsRunner.processElement(inputElem.getWindowedValue());
    }

    addWindowsRunner.finishBundle();

    context.setPCollectionValuesWithMetadata(
        context.getOutput(transform),
        executionContext.getOutput(outputTag));
  }
}
