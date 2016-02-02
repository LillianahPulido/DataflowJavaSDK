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

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.KvCoder;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.runners.worker.windmill.Windmill;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.PaneInfo;
import com.google.cloud.dataflow.sdk.util.CloudObject;
import com.google.cloud.dataflow.sdk.util.ExecutionContext;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.util.WindowedValue.FullWindowedValueCoder;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.util.common.worker.NativeReader;
import com.google.cloud.dataflow.sdk.values.KV;

import org.joda.time.Instant;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * A Reader that receives input data from a Windmill server, and returns it as
 * individual elements.
 */
class UngroupedWindmillReader<T> extends NativeReader<WindowedValue<T>> {
  private final Coder<T> valueCoder;
  private final Coder<Collection<? extends BoundedWindow>> windowsCoder;
  private StreamingModeExecutionContext context;

  UngroupedWindmillReader(Coder<WindowedValue<T>> coder, StreamingModeExecutionContext context) {
    FullWindowedValueCoder<T> inputCoder = (FullWindowedValueCoder<T>) coder;
    this.valueCoder = inputCoder.getValueCoder();
    this.windowsCoder = inputCoder.getWindowsCoder();
    this.context = context;
  }

  static class Factory implements ReaderFactory {
    @Override
    public NativeReader<?> create(
        CloudObject spec,
        @Nullable Coder<?> coder,
        @Nullable PipelineOptions options,
        @Nullable ExecutionContext executionContext,
        @Nullable CounterSet.AddCounterMutator addCounterMutator,
        @Nullable String operationName)
            throws Exception {
      @SuppressWarnings("unchecked")
      Coder<WindowedValue<Object>> typedCoder = (Coder<WindowedValue<Object>>) coder;
      return new UngroupedWindmillReader<>(
          typedCoder, (StreamingModeExecutionContext) executionContext);
    }
  }

  @Override
  public NativeReaderIterator<WindowedValue<T>> iterator() throws IOException {
    return new UngroupedWindmillReaderIterator(context.getWork());
  }

  class UngroupedWindmillReaderIterator extends WindmillReaderIteratorBase {
    UngroupedWindmillReaderIterator(Windmill.WorkItem work) {
      super(work);
    }

    @Override
    protected WindowedValue<T> decodeMessage(Windmill.Message message) throws IOException {
      Instant timestampMillis = new Instant(TimeUnit.MICROSECONDS.toMillis(message.getTimestamp()));
      InputStream data = message.getData().newInput();
      InputStream metadata = message.getMetadata().newInput();
      Collection<? extends BoundedWindow> windows = WindmillSink.decodeMetadataWindows(
          windowsCoder, message.getMetadata());
      PaneInfo pane = WindmillSink.decodeMetadataPane(message.getMetadata());
      if (valueCoder instanceof KvCoder) {
        KvCoder<?, ?> kvCoder = (KvCoder<?, ?>) valueCoder;
        InputStream key = context.getSerializedKey().newInput();
        notifyElementRead(key.available() + data.available() + metadata.available());

        @SuppressWarnings("unchecked")
        T result = (T) KV.of(
            decode(kvCoder.getKeyCoder(), key),
            decode(kvCoder.getValueCoder(), data));
        return WindowedValue.of(result, timestampMillis, windows, pane);
      } else {
        notifyElementRead(data.available() + metadata.available());
        return WindowedValue.of(decode(valueCoder, data), timestampMillis, windows, pane);
      }
    }

    private <X> X decode(Coder<X> coder, InputStream input) throws IOException {
      return coder.decode(input, Coder.Context.OUTER);
    }
  }

  @Override
  public boolean supportsRestart() {
    return true;
  }
}
