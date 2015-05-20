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

import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger.TimeDomain;

import org.joda.time.Instant;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * TimerManager that uses priority queues to manage the timers that are ready to fire.
 */
public class BatchTimerManager implements TimerManager {

  private PriorityQueue<BatchTimerManager.BatchTimer> watermarkTimers = new PriorityQueue<>(11);
  private Map<String, BatchTimerManager.BatchTimer> watermarkTagToTimer = new HashMap<>();

  private PriorityQueue<BatchTimerManager.BatchTimer> processingTimers = new PriorityQueue<>(11);
  private Map<String, BatchTimerManager.BatchTimer> processingTagToTimer = new HashMap<>();

  private Instant processingTime;

  private PriorityQueue<BatchTimerManager.BatchTimer> queue(Trigger.TimeDomain domain) {
    return Trigger.TimeDomain.EVENT_TIME.equals(domain) ? watermarkTimers : processingTimers;
  }

  private Map<String, BatchTimer> map(Trigger.TimeDomain domain) {
    return Trigger.TimeDomain.EVENT_TIME.equals(domain)
        ? watermarkTagToTimer : processingTagToTimer;
  }

  public BatchTimerManager(Instant processingTime) {
    this.processingTime = processingTime;
  }

  @Override
  public void setTimer(String tag, Instant timestamp, Trigger.TimeDomain domain) {
    BatchTimerManager.BatchTimer newTimer = new BatchTimerManager.BatchTimer(tag, timestamp);

    BatchTimerManager.BatchTimer oldTimer = map(domain).put(tag, newTimer);
    if (oldTimer != null) {
      queue(domain).remove(oldTimer);
    }
    queue(domain).add(newTimer);
  }

  @Override
  public void deleteTimer(String tag, Trigger.TimeDomain domain) {
    queue(domain).remove(map(domain).get(tag));
  }

  @Override
  public Instant currentProcessingTime() {
    return processingTime;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("BatchTimerManager [");
    for (BatchTimer timer : watermarkTimers) {
      builder.append("  ").append("Watermark ").append(timer.time).append(" = ").append(timer.tag);
    }
    for (BatchTimer timer : processingTimers) {
      builder.append("  ").append("Processing ").append(timer.time).append(" = ").append(timer.tag);
    }
    builder.append("]");
    return builder.toString();
  }

  public void advanceWatermark(TriggerExecutor<?, ?, ?, ?> triggerExecutor, Instant newWatermark)
      throws Exception {
    advance(triggerExecutor, newWatermark, TimeDomain.EVENT_TIME);
  }

  public void advanceProcessingTime(
      TriggerExecutor<?, ?, ?, ?> triggerExecutor, Instant newProcessingTime) throws Exception {
    advance(triggerExecutor, newProcessingTime, TimeDomain.PROCESSING_TIME);
    this.processingTime = newProcessingTime;
  }

  /**
   * @param domain The time domain that the tag is being fired on.
   */
  protected void fire(
      TriggerExecutor<?, ?, ?, ?> triggerExecutor, String timerTag, TimeDomain domain)
          throws Exception {
    triggerExecutor.onTimer(timerTag);
  }

  private void advance(
      TriggerExecutor<?, ?, ?, ?> triggerExecutor, Instant newTime, TimeDomain domain)
          throws Exception {

    PriorityQueue<BatchTimer> timers = queue(domain);
    Map<String, BatchTimer> map = map(domain);
    boolean shouldFire = false;

    do {
      BatchTimer timer = timers.peek();
      // Timers fire if the new time is >= the timer
      shouldFire = timer != null && !newTime.isBefore(timer.time);
      if (shouldFire) {
        // Remove before firing, so that if the trigger adds another identical
        // timer we don't remove it.
        timers.remove();
        map.remove(timer.tag);

        fire(triggerExecutor, timer.tag, domain);
      }
    } while (shouldFire);
  }

  /**
   * A timer is a tag and the time it should fire.
   */
  private static class BatchTimer implements Comparable<BatchTimer> {

    final String tag;
    final Instant time;

    public BatchTimer(String tag, Instant time) {
      this.tag = tag;
      this.time = time;
    }

    @Override
    public String toString() {
      return time + ": " + tag;
    }

    @Override
    public int compareTo(BatchTimer o) {
      return time.compareTo(o.time);
    }

    @Override
    public int hashCode() {
      return Objects.hash(time, tag);
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof BatchTimer) {
        BatchTimer that = (BatchTimer) other;
        return Objects.equals(this.time, that.time)
            && Objects.equals(this.tag, that.tag);
      }
      return false;
    }

  }
}
