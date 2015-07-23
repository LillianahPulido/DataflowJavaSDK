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

import com.google.cloud.dataflow.sdk.util.state.StateNamespace;

import org.joda.time.Instant;

/**
 * Encapsulate interaction with time within the execution environment.
 *
 * <p> This class allows setting and deleting timers, and also retrieving an
 * estimate of the current time.
 */
public interface TimerManager {

  /**
   * {@code TimeDomain} specifies whether an operation is based on
   * timestamps of elements or current "real-world" time as reported while processing.
   */
  public enum TimeDomain {
    /**
     * The {@code EVENT_TIME} domain corresponds to the timestamps on the elemnts. Time advances
     * on the system watermark advances.
     */
    EVENT_TIME,

    /**
     * The {@code PROCESSING_TIME} domain corresponds to the current to the current (system) time.
     * This is advanced during execution of the Dataflow pipeline.
     */
    PROCESSING_TIME,

    /**
     * Same as the {@code PROCESSING_TIME} domain, except it won't fire a timer set for time
     * {@code T} until all timers from earlier stages set for a time earlier than {@code T} have
     * fired.
     */
    SYNCHRONIZED_PROCESSING_TIME;
  }

  /**
   * Writes out a timer to be fired when the watermark reaches the given
   * timestamp.  Timers are identified by their name, and can be moved
   * by calling {@code setTimer} again, or deleted with {@link #deleteTimer}.
   */
  void setTimer(StateNamespace timer, Instant timestamp, TimeDomain domain);

  /**
   * Deletes the given timer.
   */
  void deleteTimer(StateNamespace timer, TimeDomain domain);

  /**
   * Returns the current timestamp in the {@link TimeDomain#PROCESSING_TIME} time domain.
   */
  Instant currentProcessingTime();

  /**
   * Returns an estimate of the current timestamp in the {@link TimeDomain#EVENT_TIME} time domain.
   */
  Instant currentWatermarkTime();
}
