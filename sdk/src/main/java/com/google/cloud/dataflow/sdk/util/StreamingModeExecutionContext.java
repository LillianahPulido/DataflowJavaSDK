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

import com.google.api.services.dataflow.model.SideInputInfo;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.io.UnboundedSource;
import com.google.cloud.dataflow.sdk.runners.worker.DataflowExecutionContext;
import com.google.cloud.dataflow.sdk.runners.worker.windmill.Windmill;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.util.StateFetcher.SideInputState;
import com.google.cloud.dataflow.sdk.util.TimerManager.TimeDomain;
import com.google.cloud.dataflow.sdk.util.state.StateInternals;
import com.google.cloud.dataflow.sdk.util.state.StateNamespace;
import com.google.cloud.dataflow.sdk.util.state.WindmillStateInternals;
import com.google.cloud.dataflow.sdk.util.state.WindmillStateReader;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ByteString;

import org.joda.time.Instant;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * {@link ExecutionContext} for use in streaming mode.
 */
public class StreamingModeExecutionContext extends DataflowExecutionContext {
  private Instant inputDataWatermark;
  private Windmill.WorkItem work;
  private StateFetcher stateFetcher;
  private Windmill.WorkItemCommitRequest.Builder outputBuilder;
  private Map<TupleTag<?>, Map<BoundedWindow, Object>> sideInputCache;

  // Per-key cache of active Reader objects in use by this process.
  private ConcurrentMap<ByteString, UnboundedSource.UnboundedReader<?>> readerCache;
  private UnboundedSource.UnboundedReader<?> activeReader;
  private ConcurrentMap<String, String> stateNameMap;
  private WindmillStateReader stateReader;

  public StreamingModeExecutionContext(
      StateFetcher stateFetcher,
      ConcurrentMap<ByteString, UnboundedSource.UnboundedReader<?>> readerCache,
      ConcurrentMap<String, String> stateNameMap) {
    this.stateFetcher = stateFetcher;
    this.sideInputCache = new HashMap<>();
    this.readerCache = readerCache;
    this.stateNameMap = stateNameMap;
  }

  public void start(
      Windmill.WorkItem work,
      Instant inputDataWatermark,
      WindmillStateReader stateReader,
      Windmill.WorkItemCommitRequest.Builder outputBuilder) {
    this.work = work;
    this.stateReader = stateReader;
    this.outputBuilder = outputBuilder;
    this.sideInputCache.clear();
    this.inputDataWatermark = inputDataWatermark;

    for (ExecutionContext.StepContext stepContext : getAllStepContexts()) {
      ((StepContext) stepContext).start(stateReader);
    }
  }

  @Override
  public ExecutionContext.StepContext createStepContext(String stepName) {
    StepContext context = new StepContext(stepName);
    context.start(stateReader);
    return context;
  }

  @Override
  public TimerManager getTimerManager() {
    return new TimerManager() {
      @Override
      public void setTimer(StateNamespace timer, Instant timestamp, TimeDomain domain) {
        long timestampMicros = TimeUnit.MILLISECONDS.toMicros(timestamp.getMillis());
        outputBuilder.addOutputTimers(
            Windmill.Timer.newBuilder()
            .setTimestamp(timestampMicros)
            .setTag(ByteString.copyFromUtf8(timer.stringKey() + "+"))
            .setType(timerType(domain))
            .build());
      }

      @Override
      public void deleteTimer(StateNamespace timer, TimeDomain domain) {
        outputBuilder.addOutputTimers(
            Windmill.Timer.newBuilder()
            .setTag(ByteString.copyFromUtf8(timer.stringKey() + "+"))
            .setType(timerType(domain))
            .build());
      }

      @Override
      public Instant currentProcessingTime() {
        return Instant.now();
      }

      @Override
      public Instant currentWatermarkTime() {
        return inputDataWatermark;
      }
    };
  }

  private Windmill.Timer.Type timerType(TimeDomain domain) {
    switch (domain) {
      case EVENT_TIME: return Windmill.Timer.Type.WATERMARK;
      case PROCESSING_TIME: return Windmill.Timer.Type.REALTIME;
      case SYNCHRONIZED_PROCESSING_TIME: return Windmill.Timer.Type.DEPENDENT_REALTIME;
      default:
        throw new IllegalArgumentException("Unrecgonized TimeDomain: " + domain);
    }
  }

  @Override
  public SideInputReader getSideInputReader(Iterable<? extends SideInputInfo> sideInputInfos) {
    throw new UnsupportedOperationException(
        "Cannot call getSideInputReader for StreamingDataflowWorker: "
        + "the MapTask specification should not have had any SideInputInfo descriptors "
        + "since the streaming runner does not yet support them.");
  }

  @Override
  public SideInputReader getSideInputReaderForViews(Iterable<? extends PCollectionView<?>> views) {
    return StreamingModeSideInputReader.of(views, this);
  }

  /**
   * Fetch the given side input asynchronously and return true if it is present.
   */
  public boolean issueSideInputFetch(
      PCollectionView<?> view, BoundedWindow mainInputWindow, SideInputState state) {
    BoundedWindow sideInputWindow =
        view.getWindowingStrategyInternal().getWindowFn().getSideInputWindow(mainInputWindow);
    return fetchSideInput(view, sideInputWindow, state) != null;
  }

  /**
   * Fetches the requested sideInput, and maintains a view of the cache that doesn't remove
   * items until the active work item is finished.
   */
  private <T> T fetchSideInput(
      PCollectionView<T> view, BoundedWindow sideInputWindow, SideInputState state) {

    Map<BoundedWindow, Object> tagCache = sideInputCache.get(view.getTagInternal());
    if (tagCache == null) {
      tagCache = new HashMap<>();
      sideInputCache.put(view.getTagInternal(), tagCache);
    }

    @SuppressWarnings("unchecked")
    T sideInput = (T) tagCache.get(sideInputWindow);
    if (sideInput == null) {
      if (state == SideInputState.CACHED_IN_WORKITEM) {
        throw new IllegalStateException(
            "Expected side input to be cached. Tag: "
            + view.getTagInternal().getId());
      }
      T typed = stateFetcher.fetchSideInput(view, sideInputWindow, state);
      sideInput = typed;
      if (sideInput != null) {
        tagCache.put(sideInputWindow, sideInput);
        return sideInput;
      } else {
        return null;
      }
    } else {
      return sideInput;
    }
  }

  @Override
  public <T, W extends BoundedWindow> void writePCollectionViewData(
      TupleTag<?> tag,
      Iterable<WindowedValue<T>> data, Coder<Iterable<WindowedValue<T>>> dataCoder,
      W window, Coder<W> windowCoder) throws IOException {
    if (getSerializedKey().size() != 0) {
      throw new IllegalStateException("writePCollectionViewData must follow a Combine.globally");
    }

    ByteString.Output dataStream = ByteString.newOutput();
    dataCoder.encode(data, dataStream, Coder.Context.OUTER);

    ByteString.Output windowStream = ByteString.newOutput();
    windowCoder.encode(window, windowStream, Coder.Context.OUTER);

    outputBuilder.addGlobalDataUpdates(
        Windmill.GlobalData.newBuilder()
        .setDataId(
            Windmill.GlobalDataId.newBuilder()
            .setTag(tag.getId())
            .setVersion(windowStream.toByteString())
            .build())
        .setData(dataStream.toByteString())
        .build());
  }

  public Iterable<Windmill.GlobalDataId> getSideInputNotifications() {
    return work.getGlobalDataIdNotificationsList();
  }

  /**
   * Note that there is data on the current key that is blocked on the given side input.
   */
  public void addBlockingSideInput(Windmill.GlobalDataRequest sideInput) {
    outputBuilder.addGlobalDataRequests(sideInput);
    outputBuilder.addGlobalDataIdRequests(sideInput.getDataId());
  }

  /**
   * Note that there is data on the current key that is blocked on the given side inputs.
   */
  public void addBlockingSideInputs(Iterable<Windmill.GlobalDataRequest> sideInputs) {
    for (Windmill.GlobalDataRequest sideInput : sideInputs) {
      addBlockingSideInput(sideInput);
    }
  }

  public ByteString getSerializedKey() {
    return work.getKey();
  }

  public long getWorkToken() {
    return work.getWorkToken();
  }

  public Windmill.WorkItem getWork() {
    return work;
  }

  public Windmill.WorkItemCommitRequest.Builder getOutputBuilder() {
    return outputBuilder;
  }

  public UnboundedSource.UnboundedReader<?> getCachedReader() {
    return readerCache.get(getSerializedKey());
  }

  public void setActiveReader(UnboundedSource.UnboundedReader<?> reader) {
    readerCache.put(getSerializedKey(), reader);
    activeReader = reader;
  }

  public UnboundedSource.CheckpointMark getReaderCheckpoint(
      Coder<? extends UnboundedSource.CheckpointMark> coder) {
    try {
      ByteString state = work.getSourceState().getState();
      if (state.isEmpty()) {
        return null;
      }
      return coder.decode(state.newInput(), Coder.Context.OUTER);
    } catch (IOException e) {
      throw new RuntimeException("Exception while decoding checkpoint", e);
    }
  }

  public Map<Long, Runnable> flushState() {
    Map<Long, Runnable> callbacks = new HashMap<>();

    for (ExecutionContext.StepContext stepContext : getAllStepContexts()) {
      ((StepContext) stepContext).flushState();
    }

    Windmill.SourceState.Builder sourceStateBuilder = Windmill.SourceState.newBuilder();

    if (activeReader != null) {
      final UnboundedSource.CheckpointMark checkpointMark = activeReader.getCheckpointMark();
      final Instant watermark = activeReader.getWatermark();
      long id = ThreadLocalRandom.current().nextLong();
      sourceStateBuilder.addFinalizeIds(id);
      callbacks.put(
          id,
          new Runnable() {
            @Override
            public void run() {
              try {
                checkpointMark.finalizeCheckpoint();
              } catch (IOException e) {
                throw new RuntimeException("Exception while finalizing checkpoint", e);
              }
            }
          });

      Coder<UnboundedSource.CheckpointMark> checkpointCoder =
          ((UnboundedSource<?, UnboundedSource.CheckpointMark>) activeReader.getCurrentSource())
              .getCheckpointMarkCoder();
      if (checkpointCoder != null) {
        ByteString.Output stream = ByteString.newOutput();
        try {
          checkpointCoder.encode(checkpointMark, stream, Coder.Context.OUTER);
        } catch (IOException e) {
          throw new RuntimeException("Exception while encoding checkpoint", e);
        }
        sourceStateBuilder.setState(stream.toByteString());
      }
      outputBuilder.setSourceStateUpdates(sourceStateBuilder.build());
      outputBuilder.setSourceWatermark(TimeUnit.MILLISECONDS.toMicros(watermark.getMillis()));
    }
    return callbacks;
  }

  public List<Long> getReadyCommitCallbackIds() {
    return work.getSourceState().getFinalizeIdsList();
  }

  class StepContext extends ExecutionContext.StepContext {
    private WindmillStateInternals stateInternals;
    private String prefix;

    public StepContext(String stepName) {
      super(stepName);

      prefix = stateNameMap.get(stepName);
      if (prefix == null) {
        prefix = stepName;
      }
    }

    /**
     * Update the {@code stateReader} used by this {@code StepContext}.
     */
    public void start(WindmillStateReader stateReader) {
      this.stateInternals = new WindmillStateInternals(prefix, stateReader);
    }

    public void flushState() {
      stateInternals.persist(outputBuilder);
    }

    @Override
    public StateInternals stateInternals() {
      return Preconditions.checkNotNull(stateInternals);
    }
  }

  /**
   * A {@link SideInputReader} that fetches side inputs from the streaming worker's
   * cache.
   */
  public static class StreamingModeSideInputReader implements SideInputReader {
    private StreamingModeExecutionContext context;
    private Set<PCollectionView<?>> viewSet;

    private StreamingModeSideInputReader(
        Iterable<? extends PCollectionView<?>> views, StreamingModeExecutionContext context) {
      this.context = context;
      this.viewSet = ImmutableSet.copyOf(views);
    }

    public static StreamingModeSideInputReader of(
        Iterable<? extends PCollectionView<?>> views, StreamingModeExecutionContext context) {
      return new StreamingModeSideInputReader(views, context);
    }

    @Override
    public <T> T get(PCollectionView<T> view, BoundedWindow window) {
      if (!contains(view)) {
        throw new RuntimeException("get() called with unknown view");
      }

      return context.fetchSideInput(view, window, SideInputState.CACHED_IN_WORKITEM);
    }

    @Override
    public <T> boolean contains(PCollectionView<T> view) {
      return viewSet.contains(view);
    }

    @Override
    public boolean isEmpty() {
      return viewSet.isEmpty();
    }
  }
}
