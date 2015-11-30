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
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger.OnceTrigger;
import com.google.cloud.dataflow.sdk.util.ExecutableTrigger;
import com.google.common.base.Preconditions;

import org.joda.time.Instant;

import java.util.Arrays;
import java.util.List;

/**
 * Create a {@link Trigger} that fires and finishes once after all of its sub-triggers have fired.
 *
 * @param <W> {@link BoundedWindow} subclass used to represent the windows used by this
 *            {@code Trigger}
 */
@Experimental(Experimental.Kind.TRIGGER)
public class AfterAll<W extends BoundedWindow> extends OnceTrigger<W> {

  private AfterAll(List<Trigger<W>> subTriggers) {
    super(subTriggers);
    Preconditions.checkArgument(subTriggers.size() > 1);
  }

  /**
   * Returns an {@code AfterAll} {@code Trigger} with the given subtriggers.
   */
  @SafeVarargs
  public static <W extends BoundedWindow> OnceTrigger<W> of(
      OnceTrigger<W>... triggers) {
    return new AfterAll<W>(Arrays.<Trigger<W>>asList(triggers));
  }

  private TriggerResult result(TriggerContext c) {
    // If all children have finished, then they must have each fired at least once.
    if (c.trigger().areAllSubtriggersFinished()) {
      return TriggerResult.FIRE_AND_FINISH;
    }

    return TriggerResult.CONTINUE;
  }

  @Override
  public TriggerResult onElement(OnElementContext c) throws Exception {
    for (ExecutableTrigger<W> subTrigger : c.trigger().unfinishedSubTriggers()) {
      // Since subTriggers are all OnceTriggers, they must either CONTINUE or FIRE_AND_FINISH.
      // invokeElement will automatically mark the finish bit if they return FIRE_AND_FINISH.
      subTrigger.invokeElement(c);
    }

    return result(c);
  }

  @Override
  public MergeResult onMerge(OnMergeContext c) throws Exception {
    // CONTINUE if merging returns CONTINUE for at least one sub-trigger
    // ALREADY_FINISHED if merging returns ALREADY_FINISHED for all sub-triggers and this
    // trigger itself was already finished in some window.
    // FIRE_AND_FINISH otherwise: It means this trigger is ready to fire (because all subtriggers
    // are satisfied) but has never fired as a whole.
    boolean anyContinue = true;
    boolean alreadyFinished = true;
    for (ExecutableTrigger<W> subTrigger : c.trigger().subTriggers()) {
      MergeResult result = subTrigger.invokeMerge(c);
      anyContinue |= !(result.isFire() && result.isFinish());
      alreadyFinished &= !result.isFire() && result.isFinish();
    }

    if (anyContinue) {
      return MergeResult.CONTINUE;
    } else if (alreadyFinished && c.trigger().finishedInAnyMergingWindow()) {
      return MergeResult.ALREADY_FINISHED;
    } else {
      return MergeResult.FIRE_AND_FINISH;
    }
  }

  @Override
  public TriggerResult onTimer(OnTimerContext c) throws Exception {
    for (ExecutableTrigger<W> subTrigger : c.trigger().unfinishedSubTriggers()) {
      // Since subTriggers are all OnceTriggers, they must either CONTINUE or FIRE_AND_FINISH.
      // invokeTimer will automatically mark the finish bit if they return FIRE_AND_FINISH.
      subTrigger.invokeTimer(c);
    }

    return result(c);
  }

  @Override
  public Instant getWatermarkThatGuaranteesFiring(W window) {
    // This trigger will fire after the latest of its sub-triggers.
    Instant deadline = BoundedWindow.TIMESTAMP_MIN_VALUE;
    for (Trigger<W> subTrigger : subTriggers) {
      Instant subDeadline = subTrigger.getWatermarkThatGuaranteesFiring(window);
      if (deadline.isBefore(subDeadline)) {
        deadline = subDeadline;
      }
    }
    return deadline;
  }

  @Override
  public OnceTrigger<W> getContinuationTrigger(List<Trigger<W>> continuationTriggers) {
    return new AfterAll<W>(continuationTriggers);
  }
}
