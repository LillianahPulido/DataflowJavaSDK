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

import com.google.cloud.dataflow.sdk.annotations.Experimental;
import com.google.cloud.dataflow.sdk.coders.InstantCoder;
import com.google.cloud.dataflow.sdk.transforms.SerializableFunction;
import com.google.cloud.dataflow.sdk.util.TimerManager.TimeDomain;
import com.google.cloud.dataflow.sdk.values.CodedTupleTag;

import org.joda.time.Instant;

import java.util.List;
import java.util.Objects;

/**
 * <p>{@code AfterWatermark} triggers fire based on progress of the system watermark. This time is a
 * lower-bound, sometimes heuristically established, on event times that have been fully processed
 * by the pipeline.
 *
 * <p>For sources that provide non-heuristic watermarks (e.g.
 * {@link com.google.cloud.dataflow.sdk.io.PubsubIO} when using arrival times as event times), the
 * watermark is a strict guarantee that no data with an event time earlier than
 * that watermark will ever be observed in the pipeline. In this case, it's safe to assume that any
 * pane triggered by an {@code AfterWatermark} trigger with a reference point at or beyond the end
 * of the window will be the last pane ever for that window.
 *
 * <p>For sources that provide heuristic watermarks (e.g.
 * {@link com.google.cloud.dataflow.sdk.io.PubsubIO} when using user-supplied event times), the
 * watermark itself becomes an <i>estimate</i> that no data with an event time earlier than that
 * watermark (i.e. "late data) will ever be observed in the pipeline. These heuristics can
 * often be quite accurate, but the chance of seeing late data for any given window is non-zero.
 * Thus, if absolute correctness over time is important to your use case, you may want to consider
 * using a trigger that accounts for late data. The default trigger,
 * {@code Repeatedly.forever(AfterWatermark.pastEndOfWindow())}, which fires
 * once when the watermark passes the end of the window and then immediately therafter when any
 * late data arrive, is one such example.
 *
 * <p> The watermark is the clock that defines {@link TimeDomain#EVENT_TIME}.
 *
 * @param <W> {@link BoundedWindow} subclass used to represent the windows used.
 */
@Experimental(Experimental.Kind.TRIGGER)
public abstract class AfterWatermark<W extends BoundedWindow>
    extends TimeTrigger<W, AfterWatermark<W>> {

  private static final long serialVersionUID = 0L;

  protected AfterWatermark(List<SerializableFunction<Instant, Instant>> transforms) {
    super(transforms);
  }

  /**
   * Creates a trigger that fires when the watermark passes timestamp of the first element added to
   * the pane.
   */
  static <W extends BoundedWindow> AfterWatermark<W> pastFirstElementInPane() {
    return new FromFirstElementInPane<W>(IDENTITY);
  }

  /**
   * Creates a trigger that fires when the watermark passes the end of the window.
   */
  public static <W extends BoundedWindow> AfterWatermark<W> pastEndOfWindow() {
    return new FromEndOfWindow<W>(IDENTITY);
  }

  private static class FromFirstElementInPane<W extends BoundedWindow> extends AfterWatermark<W> {

    private static final long serialVersionUID = 0L;

    private static final CodedTupleTag<Instant> DELAYED_UNTIL_TAG =
        CodedTupleTag.of("delayed-until", InstantCoder.of());

    private FromFirstElementInPane(
        List<SerializableFunction<Instant, Instant>> delayFunction) {
      super(delayFunction);
    }

    @Override
    public TriggerResult onElement(OnElementContext c) throws Exception {
      Instant delayUntil = c.lookup(DELAYED_UNTIL_TAG, c.window());
      if (delayUntil == null) {
        delayUntil = computeTargetTimestamp(c.eventTimestamp());
        c.setTimer(delayUntil, TimeDomain.EVENT_TIME);
        c.store(DELAYED_UNTIL_TAG, c.window(), delayUntil);
      }

      return TriggerResult.CONTINUE;
    }

    @Override
    public MergeResult onMerge(OnMergeContext c) throws Exception {
      // If the watermark time timer has fired in any of the windows being merged, it would have
      // fired at the same point if it had been added to the merged window. So, we just record it as
      // finished.
      if (c.finishedInAnyMergingWindow()) {
        return MergeResult.ALREADY_FINISHED;
      }

      // To have gotten here, we must not have fired in any of the oldWindows. Determine the event
      // timestamp from the minimum (we could also just pick one, or try to record the arrival times
      // of this first element in each pane).
      Instant earliestTimer = BoundedWindow.TIMESTAMP_MAX_VALUE;
      for (Instant delayedUntil : c.lookup(DELAYED_UNTIL_TAG, c.oldWindows()).values()) {
        if (delayedUntil != null && delayedUntil.isBefore(earliestTimer)) {
          earliestTimer = delayedUntil;
        }
      }

      if (earliestTimer != null) {
        c.store(DELAYED_UNTIL_TAG, c.window(), earliestTimer);
        c.setTimer(earliestTimer, TimeDomain.EVENT_TIME);
      }

      return MergeResult.CONTINUE;
    }

    @Override
    public TriggerResult onTimer(OnTimerContext c) throws Exception {
      return TriggerResult.FIRE_AND_FINISH;
    }

    @Override
    public void clear(TriggerContext c) throws Exception {
      c.remove(DELAYED_UNTIL_TAG, c.window());
      c.deleteTimer(TimeDomain.EVENT_TIME);
    }

    @Override
    public Instant getWatermarkThatGuaranteesFiring(W window) {
      return computeTargetTimestamp(window.maxTimestamp());
    }

    @Override
    protected AfterWatermark<W> newWith(
        List<SerializableFunction<Instant, Instant>> transforms) {
      return new FromFirstElementInPane<W>(transforms);
    }

    @Override
    public OnceTrigger<W> getContinuationTrigger(List<Trigger<W>> continuationTriggers) {
      return this;
    }

    @Override
    public String toString() {
      return "AfterWatermark.pastFirstElementInPane(" + timestampMappers + ")";
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof FromFirstElementInPane)) {
        return false;
      }
      FromFirstElementInPane<?> that = (FromFirstElementInPane<?>) obj;
      return Objects.equals(this.timestampMappers, that.timestampMappers);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(timestampMappers);
    }
  }

  private static class FromEndOfWindow<W extends BoundedWindow> extends AfterWatermark<W> {

    private static final long serialVersionUID = 0L;

    private FromEndOfWindow(
        List<SerializableFunction<Instant, Instant>> composed) {
      super(composed);
    }

    @Override
    public TriggerResult onElement(OnElementContext c) throws Exception {
      c.setTimer(computeTargetTimestamp(c.window().maxTimestamp()), TimeDomain.EVENT_TIME);
      return TriggerResult.CONTINUE;
    }

    @Override
    public MergeResult onMerge(OnMergeContext c) throws Exception {
      // If the watermark was past the end of a window that is past the end of the new window,
      // then the watermark must also be past the end of this window. What's more, we've already
      // fired some elements for that trigger firing, so we report FINISHED (without firing).
      for (W finishedWindow : c.getFinishedMergingWindows()) {
        if (finishedWindow.maxTimestamp().isAfter(c.window().maxTimestamp())) {
          return MergeResult.ALREADY_FINISHED;
        }
      }

      // Otherwise, set a timer for this window, and return.
      c.setTimer(computeTargetTimestamp(c.window().maxTimestamp()), TimeDomain.EVENT_TIME);
      return MergeResult.CONTINUE;
    }

    @Override
    public TriggerResult onTimer(OnTimerContext c) throws Exception {
      return TriggerResult.FIRE_AND_FINISH;
    }

    @Override
    public void clear(TriggerContext c) throws Exception {
      c.deleteTimer(TimeDomain.EVENT_TIME);
    }

    @Override
    public Instant getWatermarkThatGuaranteesFiring(W window) {
      return computeTargetTimestamp(window.maxTimestamp());
    }

    @Override
    protected AfterWatermark<W> newWith(
        List<SerializableFunction<Instant, Instant>> transforms) {
      return new FromEndOfWindow<>(transforms);
    }

    @Override
    public OnceTrigger<W> getContinuationTrigger(List<Trigger<W>> continuationTriggers) {
      return this;
    }

    @Override
    public String toString() {
      return "AfterWatermark.pastEndOfWindow(" + timestampMappers + ")";
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof FromEndOfWindow)) {
        return false;
      }
      FromEndOfWindow<?> that = (FromEndOfWindow<?>) obj;
      return Objects.equals(this.timestampMappers, that.timestampMappers);
    }

    @Override
    public int hashCode() {
      return Objects.hash(getClass(), timestampMappers);
    }
  }
}
