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
import com.google.cloud.dataflow.sdk.coders.MapCoder;
import com.google.cloud.dataflow.sdk.coders.Proto2Coder;
import com.google.cloud.dataflow.sdk.coders.SetCoder;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.runners.worker.windmill.Windmill;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.util.ExecutionContext.StepContext;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.values.CodedTupleTag;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.common.base.Throwables;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs a DoFn by constructing the appropriate contexts and passing them in.
 *
 * @param <I> the type of the DoFn's (main) input elements
 * @param <O> the type of the DoFn's (main) output elements
 * @param <R> the type of object that receives outputs
 * @param <W> the type of the windows of the main input
 */
public class StreamingSideInputDoFnRunner<I, O, R, W extends BoundedWindow>
    extends DoFnRunner<I, O, R> {
  private StepContext stepContext;
  private StreamingModeExecutionContext execContext;
  private WindowingStrategy<?, W> windowingStrategy;
  private Map<String, PCollectionView<?>> sideInputViews;
  private CodedTupleTag<Map<W, Set<Windmill.GlobalDataRequest>>> blockedMapTag;
  private Map<W, Set<Windmill.GlobalDataRequest>> blockedMap;
  private Coder<I> elemCoder;

  public StreamingSideInputDoFnRunner(
      PipelineOptions options,
      DoFnInfo<I, O> doFnInfo,
      PTuple sideInputs,
      OutputManager<R> outputManager,
      TupleTag<O> mainOutputTag,
      List<TupleTag<?>> sideOutputTags,
      StepContext stepContext,
      CounterSet.AddCounterMutator addCounterMutator) throws Exception {
    super(options, doFnInfo.getDoFn(), sideInputs, outputManager,
        mainOutputTag, sideOutputTags, stepContext,
        addCounterMutator, doFnInfo.getWindowingStrategy());
    this.stepContext = stepContext;
    this.windowingStrategy = (WindowingStrategy) doFnInfo.getWindowingStrategy();
    this.elemCoder = doFnInfo.getInputCoder();

    this.sideInputViews = new HashMap<>();
    for (PCollectionView<?> view : doFnInfo.getSideInputViews()) {
      sideInputViews.put(view.getTagInternal().getId(), view);
    }
    this.execContext =
        (StreamingModeExecutionContext) stepContext.getExecutionContext();
    this.blockedMapTag = CodedTupleTag.of("blockedMap:", MapCoder.of(
        windowingStrategy.getWindowFn().windowCoder(),
        SetCoder.of(Proto2Coder.of(Windmill.GlobalDataRequest.class))));
    this.blockedMap = stepContext.lookup(blockedMapTag);
    if (this.blockedMap == null) {
      this.blockedMap = new HashMap<>();
    }
  }

  @Override
  public void startBundle() {
    super.startBundle();

    Map<W, CodedTupleTag<WindowedValue<I>>> readyWindowTags = new HashMap<>();

    for (Windmill.GlobalDataId id : execContext.getSideInputNotifications()) {
      PCollectionView<?> view = sideInputViews.get(id.getTag());
      if (view == null) {
        // Side input is for a different DoFn; ignore it.
        continue;
      }

      for (Map.Entry<W, Set<Windmill.GlobalDataRequest>> entry : blockedMap.entrySet()) {
        Set<Windmill.GlobalDataRequest> found = new HashSet<>();
        for (Windmill.GlobalDataRequest request : entry.getValue()) {
          if (id.equals(request.getDataId())) {
            found.add(request);
          }
        }
        entry.getValue().removeAll(found);
        if (entry.getValue().isEmpty()) {
          try {
            readyWindowTags.put(entry.getKey(), getElemListTag(entry.getKey()));
          } catch (IOException e) {
            throw Throwables.propagate(e);
          }
        }
      }
    }

    Map<CodedTupleTag<WindowedValue<I>>, Iterable<WindowedValue<I>>> elementsPerWindow;
    try {
      elementsPerWindow = stepContext.readTagLists(readyWindowTags.values());
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }

    for (Map.Entry<W, CodedTupleTag<WindowedValue<I>>> entry : readyWindowTags.entrySet()) {
      blockedMap.remove(entry.getKey());

      Iterable<WindowedValue<I>> elements = elementsPerWindow.get(entry.getValue());
      try {
        for (WindowedValue<I> elem : elements) {
          fn.processElement(createProcessContext(elem));
        }
      } catch (Throwable t) {
        // Exception in user code.
        Throwables.propagateIfInstanceOf(t, UserCodeException.class);
        throw new UserCodeException(t);
      }

      stepContext.deleteTagList(entry.getValue());
    }
  }

  @Override
  public void invokeProcessElement(WindowedValue<I> elem) {
    // This can contain user code. Wrap it in case it throws an exception.
    try {
      W window = (W) elem.getWindows().iterator().next();

      Set<Windmill.GlobalDataRequest> blocked = blockedMap.get(window);
      if (blocked == null) {
        for (PCollectionView<?> view : sideInputViews.values()) {
          if (!execContext.issueSideInputFetch(view, window)) {
            if (blocked == null) {
              blocked = new HashSet<>();
              blockedMap.put(window, blocked);
            }
            Coder<BoundedWindow> sideInputWindowCoder =
                view.getWindowingStrategyInternal().getWindowFn().windowCoder();

            BoundedWindow sideInputWindow =
                view.getWindowingStrategyInternal().getWindowFn().getSideInputWindow(window);

            ByteString.Output windowStream = ByteString.newOutput();
            sideInputWindowCoder.encode(sideInputWindow, windowStream, Coder.Context.OUTER);

            blocked.add(Windmill.GlobalDataRequest.newBuilder()
                .setDataId(Windmill.GlobalDataId.newBuilder()
                    .setTag(view.getTagInternal().getId())
                    .setVersion(windowStream.toByteString())
                    .build())
                .setExistenceWatermarkDeadline(
                    TimeUnit.MILLISECONDS.toMicros(view.getWindowingStrategyInternal()
                        .getTrigger()
                        .getWatermarkCutoff(sideInputWindow)
                        .getMillis()))
                .build());
          }
        }
      }

      if (blocked == null) {
        fn.processElement(createProcessContext(elem));
      } else {
        stepContext.writeToTagList(
            getElemListTag(window), elem, elem.getTimestamp());

        execContext.setBlockingSideInputs(blocked);
      }
    } catch (Throwable t) {
      // Exception in user code.
      Throwables.propagateIfInstanceOf(t, UserCodeException.class);
      throw new UserCodeException(t);
    }
  }

  @Override
  public void finishBundle() {
    super.finishBundle();
    try {
      stepContext.store(blockedMapTag, blockedMap);
    } catch (IOException e) {
      throw new RuntimeException("Exception while storing streaming side input info: ", e);
    }
  }

  private CodedTupleTag<WindowedValue<I>> getElemListTag(W window) throws IOException {
    return CodedTupleTag.<WindowedValue<I>>of(
        "e:" + CoderUtils.encodeToBase64(windowingStrategy.getWindowFn().windowCoder(), window),
        WindowedValue.getFullCoder(elemCoder, windowingStrategy.getWindowFn().windowCoder()));
  }
}
