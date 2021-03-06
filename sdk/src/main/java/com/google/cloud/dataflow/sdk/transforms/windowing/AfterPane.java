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
import com.google.cloud.dataflow.sdk.coders.VarLongCoder;
import com.google.cloud.dataflow.sdk.transforms.Sum;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger.OnceTrigger;
import com.google.cloud.dataflow.sdk.util.ReduceFn.MergingStateContext;
import com.google.cloud.dataflow.sdk.util.ReduceFn.StateContext;
import com.google.cloud.dataflow.sdk.util.state.CombiningValueState;
import com.google.cloud.dataflow.sdk.util.state.StateTag;
import com.google.cloud.dataflow.sdk.util.state.StateTags;

import org.joda.time.Instant;

import java.util.List;
import java.util.Objects;

/**
 * {@link Trigger}s that fire based on properties of the elements in the current pane.
 *
 * @param <W> {@link BoundedWindow} subclass used to represent the windows used by this
 *            {@link Trigger}
 */
@Experimental(Experimental.Kind.TRIGGER)
public class AfterPane<W extends BoundedWindow> extends OnceTrigger<W>{

  private static final StateTag<CombiningValueState<Long, Long>> ELEMENTS_IN_PANE_TAG =
      StateTags.makeSystemTagInternal(StateTags.combiningValueFromInputInternal(
          "count", VarLongCoder.of(), new Sum.SumLongFn()));

  private final int countElems;

  private AfterPane(int countElems) {
    super(null);
    this.countElems = countElems;
  }

  /**
   * Creates a trigger that fires when the pane contains at least {@code countElems} elements.
   */
  public static <W extends BoundedWindow> AfterPane<W> elementCountAtLeast(int countElems) {
    return new AfterPane<>(countElems);
  }

  @Override
  public void onElement(OnElementContext c) throws Exception {
    c.state().access(ELEMENTS_IN_PANE_TAG).add(1L);
  }

  @Override
  public void prefetchOnMerge(MergingStateContext state) {
    state.mergingAccess(ELEMENTS_IN_PANE_TAG).get();
  }

  @Override
  public void onMerge(OnMergeContext context) throws Exception {
    if (context.trigger().finishedInAnyMergingWindow()) {
      context.trigger().setFinished(true);
      return;
    }

    // Eagerly merge
    long count = context.state().mergingAccess(ELEMENTS_IN_PANE_TAG).get().read();
    context.state().mergingAccess(ELEMENTS_IN_PANE_TAG).clear();
    context.state().access(ELEMENTS_IN_PANE_TAG).add(count);
  }

  @Override
  public void prefetchShouldFire(StateContext state) {
    state.access(ELEMENTS_IN_PANE_TAG).get();
  }

  @Override
  public boolean shouldFire(Trigger<W>.TriggerContext context) throws Exception {
    long count = context.state().access(ELEMENTS_IN_PANE_TAG).get().read();
    return count >= countElems;
  }

  @Override
  public void clear(TriggerContext c) throws Exception {
    c.state().access(ELEMENTS_IN_PANE_TAG).clear();
  }

  @Override
  public boolean isCompatible(Trigger<?> other) {
    return this.equals(other);
  }

  @Override
  public Instant getWatermarkThatGuaranteesFiring(W window) {
    return BoundedWindow.TIMESTAMP_MAX_VALUE;
  }

  @Override
  public OnceTrigger<W> getContinuationTrigger(List<Trigger<W>> continuationTriggers) {
    return AfterPane.elementCountAtLeast(1);
  }

  @Override
  public String toString() {
    return "AfterPane.elementCountAtLeast(" + countElems + ")";
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof AfterPane)) {
      return false;
    }
    AfterPane<?> that = (AfterPane<?>) obj;
    return this.countElems == that.countElems;
  }

  @Override
  public int hashCode() {
    return Objects.hash(countElems);
  }

  @Override
  protected void onOnlyFiring(Trigger<W>.TriggerContext context) throws Exception {
    clear(context);
  }
}
