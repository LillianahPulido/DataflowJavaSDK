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
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.PaneInfo;
import com.google.cloud.dataflow.sdk.util.ReduceFn.MergingStateContext;
import com.google.cloud.dataflow.sdk.util.ReduceFn.StateContext;
import com.google.cloud.dataflow.sdk.util.state.MergeableState;
import com.google.cloud.dataflow.sdk.util.state.State;
import com.google.cloud.dataflow.sdk.util.state.StateInternals;
import com.google.cloud.dataflow.sdk.util.state.StateNamespace;
import com.google.cloud.dataflow.sdk.util.state.StateNamespaces;
import com.google.cloud.dataflow.sdk.util.state.StateTag;
import com.google.common.collect.ImmutableMap;

import org.joda.time.Instant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Factory for creating instances of the various {@link ReduceFn} contexts.
 */
class ReduceFnContextFactory<K, InputT, OutputT, W extends BoundedWindow> {

  public interface OnTriggerCallbacks<OutputT> {
    void output(OutputT toOutput);
  }

  private final K key;
  private final ReduceFn<K, InputT, OutputT, W> reduceFn;
  private final WindowingStrategy<?, W> windowingStrategy;
  private StateInternals stateInternals;
  private ActiveWindowSet<W> activeWindows;

  ReduceFnContextFactory(
      K key, ReduceFn<K, InputT, OutputT, W> reduceFn, WindowingStrategy<?, W> windowingStrategy,
      StateInternals stateInternals, ActiveWindowSet<W> activeWindows) {
    this.key = key;
    this.reduceFn = reduceFn;
    this.windowingStrategy = windowingStrategy;
    this.stateInternals = stateInternals;
    this.activeWindows = activeWindows;
  }

  private StateContextImpl<W> stateContext(W window) {
    return new StateContextImpl<>(
        activeWindows, windowingStrategy.getWindowFn().windowCoder(), stateInternals, window);
  }

  public ReduceFn<K, InputT, OutputT, W>.Context base(W window) {
    return new ContextImpl(stateContext(window));
  }

  public ReduceFn<K, InputT, OutputT, W>.ProcessValueContext forValue(
      W window, InputT value, Instant timestamp) {
    return new ProcessValueContextImpl(stateContext(window), value, timestamp);
  }

  public ReduceFn<K, InputT, OutputT, W>.OnTriggerContext forTrigger(
      W window, PaneInfo pane, OnTriggerCallbacks<OutputT> callbacks) {
    return new OnTriggerContextImpl(stateContext(window), pane, callbacks);
  }

  public ReduceFn<K, InputT, OutputT, W>.OnMergeContext forMerge(
      Collection<W> mergingWindows, W resultWindow) {
    return new OnMergeContextImpl(
        new MergingStateContextImpl<W>(stateContext(resultWindow), mergingWindows));
  }

  static class StateContextImpl<W extends BoundedWindow> implements ReduceFn.StateContext {

    private final ActiveWindowSet<W> activeWindows;
    private final W window;
    protected StateNamespace namespace;
    protected final Coder<W> windowCoder;
    private final StateInternals stateInternals;

    public StateContextImpl(
        ActiveWindowSet<W> activeWindows,
        Coder<W> windowCoder,
        StateInternals stateInternals,
        W window) {
      this.activeWindows = activeWindows;
      this.windowCoder = windowCoder;
      this.stateInternals = stateInternals;
      this.window = window;
      this.namespace = namespaceFor(window);
    }

    protected StateNamespace namespaceFor(W window) {
      return StateNamespaces.window(windowCoder, window);
    }

    W window() {
      return window;
    }

    StateNamespace namespace() {
      return namespace;
    }

    @Override
    public <StorageT extends State> StorageT access(StateTag<StorageT> address) {
      return stateInternals.state(namespace, address);
    }

    @Override
    public <StorageT extends MergeableState<?, ?>> StorageT accessAcrossMergedWindows(
        StateTag<StorageT> address) {
      List<StateNamespace> sourceNamespaces = new ArrayList<>();
      for (W sourceWindow : activeWindows.sourceWindows(window)) {
        sourceNamespaces.add(namespaceFor(sourceWindow));
      }

      return stateInternals.mergedState(sourceNamespaces, namespace, address);
    }
  }

  static class MergingStateContextImpl<W extends BoundedWindow>
      implements ReduceFn.MergingStateContext {

    private final StateContextImpl<W> delegate;
    private final Collection<W> mergingWindows;

    public MergingStateContextImpl(StateContextImpl<W> delegate, Collection<W> mergingWindows) {
      this.delegate = delegate;
      this.mergingWindows = mergingWindows;
    }

    StateNamespace namespace() {
      return delegate.namespace;
    }

    W window() {
      return delegate.window();
    }

    Collection<W> mergingWindows() {
      return mergingWindows;
    }

    @Override
    public <StorageT extends State> StorageT access(StateTag<StorageT> address) {
      return delegate.access(address);
    }

    @Override
    public <StorageT extends MergeableState<?, ?>> StorageT accessAcrossMergedWindows(
        StateTag<StorageT> address) {
      return delegate.accessAcrossMergedWindows(address);
    }

    @Override
    public <StateT extends MergeableState<?, ?>> StateT accessAcrossMergingWindows(
        StateTag<StateT> address) {
      List<StateNamespace> mergingNamespaces = new ArrayList<>();
      for (W mergingWindow : mergingWindows) {
        mergingNamespaces.add(delegate.namespaceFor(mergingWindow));
      }

      return delegate.stateInternals.mergedState(mergingNamespaces, delegate.namespace, address);
    }

    @Override
    public <StateT extends State> Map<BoundedWindow, StateT> accessInEachMergingWindow(
        StateTag<StateT> address) {
      ImmutableMap.Builder<BoundedWindow, StateT> builder = ImmutableMap.builder();
      for (W mergingWindow : mergingWindows) {
        StateT stateForWindow = delegate.stateInternals.state(
            delegate.namespaceFor(mergingWindow), address);
        builder.put(mergingWindow, stateForWindow);
      }
      return builder.build();
    }
  }

  private class ContextImpl extends ReduceFn<K, InputT, OutputT, W>.Context {

    private StateContextImpl<W> state;

    private ContextImpl(StateContextImpl<W> state) {
      reduceFn.super();
      this.state = state;
    }

    @Override
    public K key() {
      return key;
    }

    @Override
    public W window() {
      return state.window;
    }

    @Override
    public WindowingStrategy<?, W> windowingStrategy() {
      return windowingStrategy;
    }

    @Override
    public StateContext state() {
      return state;
    }
  }

  private class ProcessValueContextImpl
      extends ReduceFn<K, InputT, OutputT, W>.ProcessValueContext {

    private InputT value;
    private Instant timestamp;
    private StateContextImpl<W> state;

    private ProcessValueContextImpl(StateContextImpl<W> state, InputT value, Instant timestamp) {
      reduceFn.super();
      this.state = state;
      this.value = value;
      this.timestamp = timestamp;
    }

    @Override
    public K key() {
      return key;
    }

    @Override
    public W window() {
      return state.window;
    }

    @Override
    public WindowingStrategy<?, W> windowingStrategy() {
      return windowingStrategy;
    }

    @Override
    public StateContext state() {
      return state;
    }

    @Override
    public InputT value() {
      return value;
    }

    @Override
    public Instant timestamp() {
      return timestamp;
    }
  }

  private class OnTriggerContextImpl
      extends ReduceFn<K, InputT, OutputT, W>.OnTriggerContext {

    private final StateContextImpl<W> state;
    private final PaneInfo paneInfo;
    private final OnTriggerCallbacks<OutputT> callbacks;

    private OnTriggerContextImpl(StateContextImpl<W> state,
        PaneInfo paneInfo, OnTriggerCallbacks<OutputT> callbacks) {
      reduceFn.super();
      this.state = state;
      this.paneInfo = paneInfo;
      this.callbacks = callbacks;
    }

    @Override
    public K key() {
      return key;
    }

    @Override
    public W window() {
      return state.window;
    }

    @Override
    public WindowingStrategy<?, W> windowingStrategy() {
      return windowingStrategy;
    }

    @Override
    public StateContext state() {
      return state;
    }

    @Override
    public PaneInfo paneInfo() {
      return paneInfo;
    }

    @Override
    public void output(OutputT value) {
      callbacks.output(value);
    }
  }

  private class OnMergeContextImpl
      extends ReduceFn<K, InputT, OutputT, W>.OnMergeContext {

    private final MergingStateContextImpl<W> state;

    private OnMergeContextImpl(MergingStateContextImpl<W> state) {
      reduceFn.super();
      this.state = state;
    }

    @Override
    public K key() {
      return key;
    }

    @Override
    public WindowingStrategy<?, W> windowingStrategy() {
      return windowingStrategy;
    }

    @Override
    public MergingStateContext state() {
      return state;
    }

    @Override
    public Collection<W> mergingWindows() {
      return state.mergingWindows;
    }

    @Override
    public W window() {
      return state.delegate.window;
    }
  }
}
