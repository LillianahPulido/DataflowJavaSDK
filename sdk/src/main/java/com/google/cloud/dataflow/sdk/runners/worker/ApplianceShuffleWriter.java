/*******************************************************************************
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
 ******************************************************************************/

package com.google.cloud.dataflow.sdk.runners.worker;

import com.google.cloud.dataflow.sdk.util.common.CounterSet;

import java.io.IOException;
import javax.annotation.concurrent.ThreadSafe;

/**
 * ApplianceShuffleWriter writes chunks of data to a shuffle dataset.
 *
 * <p>It is a JNI wrapper of an equivalent C++ class.
 */
@ThreadSafe
public final class ApplianceShuffleWriter implements ShuffleWriter {
  static {
    ShuffleLibrary.load();
  }

  /**
   * Pointer to the underlying native shuffle writer code.
   */
  private long nativePointer;

  /**
   * Mutator that can be used to update counters.
   */
  private final CounterSet.AddCounterMutator addCounterMutator;

  /**
   * @param shuffleWriterConfig opaque configuration for creating a
   * shuffle writer
   * @param bufferSize the writer buffer size
   * @param addCounterMutator mutator that can be used to update counters
   */
  public ApplianceShuffleWriter(
      byte[] shuffleWriterConfig,
      long bufferSize,
      CounterSet.AddCounterMutator addCounterMutator) {
    this.nativePointer = createFromConfig(shuffleWriterConfig, bufferSize);
    this.addCounterMutator = addCounterMutator;
  }

  @Override
  public void finalize() {
    destroy();
  }

  /**
   * Native methods for interacting with the underlying native shuffle
   * writer code.
   */
  private native long createFromConfig(byte[] shuffleWriterConfig,
                                       long bufferSize);
  private native void destroy();

  public native String getDatasetId();

  @Override
  public native void write(byte[] chunk) throws IOException;

  @Override
  public native void close() throws IOException;
}
