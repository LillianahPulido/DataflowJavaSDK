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
package com.google.cloud.dataflow.sdk.util.state;

import com.google.cloud.dataflow.sdk.annotations.Experimental;
import com.google.cloud.dataflow.sdk.annotations.Experimental.Kind;
import com.google.cloud.dataflow.sdk.coders.CannotProvideCoderException;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.CoderRegistry;
import com.google.cloud.dataflow.sdk.transforms.Combine.CombineFn;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.OutputTimeFn;
import com.google.common.base.MoreObjects;

import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;

/**
 * Static utility methods for creating {@link StateTag} instances.
 */
@Experimental(Kind.STATE)
public class StateTags {

  private static final CoderRegistry STANDARD_REGISTRY = new CoderRegistry();
  static {
    STANDARD_REGISTRY.registerStandardCoders();
  }

  private enum StateKind {
    SYSTEM('s'),
    USER('u');

    private char prefix;

    StateKind(char prefix) {
      this.prefix = prefix;
    }
  }

  private StateTags() { }

  /**
   * Create a simple state tag for values of type {@code T}.
   */
  public static <T> StateTag<ValueState<T>> value(String id, Coder<T> valueCoder) {
    return new ValueStateTag<>(new StructuredId(id), valueCoder);
  }

  /**
   * Create a state tag for values that use a {@link CombineFn} to automatically merge
   * multiple {@code InputT}s into a single {@code OutputT}.
   */
  public static <InputT, AccumT, OutputT> StateTag<CombiningValueState<InputT, OutputT>>
  combiningValue(
      String id, Coder<AccumT> accumCoder, CombineFn<InputT, AccumT, OutputT> combineFn) {
    return combiningValueInternal(id, accumCoder, combineFn);
  }

  /**
   * Create a state tag for values that use a {@link CombineFn} to automatically merge
   * multiple {@code InputT}s into a single {@code OutputT}.
   *
   * <p>This determines the {@code Coder<AccumT>} from the given {@code Coder<InputT>}, and
   * should only be used to initialize static values.
   */
  public static <InputT, AccumT, OutputT> StateTag<CombiningValueState<InputT, OutputT>>
  combiningValueFromInputInternal(
      String id, Coder<InputT> inputCoder, CombineFn<InputT, AccumT, OutputT> combineFn) {
    try {
      Coder<AccumT> accumCoder = combineFn.getAccumulatorCoder(STANDARD_REGISTRY, inputCoder);
      return combiningValueInternal(id, accumCoder, combineFn);
    } catch (CannotProvideCoderException e) {
      throw new IllegalArgumentException(
          "Unable to determine accumulator coder for " + combineFn.getClass().getSimpleName()
          + " from " + inputCoder, e);
    }
  }

  private static <InputT, AccumT, OutputT> StateTag<CombiningValueState<InputT, OutputT>>
  combiningValueInternal(
      String id, Coder<AccumT> accumCoder, CombineFn<InputT, AccumT, OutputT> combineFn) {
    StateTag<CombiningValueStateInternal<InputT, AccumT, OutputT>> internal =
        new CombiningValueStateTag<InputT, AccumT, OutputT>(
            new StructuredId(id), accumCoder, combineFn);

    // This is a safe cast, since StateTag only supports reading, and
    // CombiningValue<InputT, OutputT> is a super-interface of
    // CombiningValueInternal<InputT, AccumT, OutputT>
    @SuppressWarnings({"unchecked", "rawtypes"})
    StateTag<CombiningValueState<InputT, OutputT>> external = (StateTag) internal;
    return external;
  }

  /**
   * Create a state tag that is optimized for adding values frequently, and
   * occasionally retrieving all the values that have been added.
   */
  public static <T> StateTag<BagState<T>> bag(String id, Coder<T> elemCoder) {
    return new BagStateTag<T>(new StructuredId(id), elemCoder);
  }

  /**
   * Create a state tag for holding the watermark.
   */
  public static <T, W extends BoundedWindow> StateTag<WatermarkStateInternal>
      watermarkStateInternal(String id, OutputTimeFn<W> outputTimeFn) {
    return new WatermarkStateTagInternal<W>(new StructuredId(id), outputTimeFn);
  }

  /**
   * Convert an arbitrary {@code StateTag} to a system-internal tag that is guaranteed not to
   * collide with any user tags.
   */
  public static <StateT extends State> StateTag<StateT> makeSystemTagInternal(
      StateTag<StateT> tag) {
    if (!(tag instanceof StateTagBase)) {
      throw new IllegalArgumentException("Expected subclass of StateTagBase, got " + tag);
    }
    return ((StateTagBase<StateT>) tag).asKind(StateKind.SYSTEM);
  }

  public static <InputT, AccumT, OutputT> StateTag<BagState<AccumT>> convertToBagTagInternal(
      StateTag<CombiningValueStateInternal<InputT, AccumT, OutputT>> combiningTag) {
    if (!(combiningTag instanceof CombiningValueStateTag)) {
      throw new IllegalArgumentException("Unexpected StateTag " + combiningTag);
    }
    return ((CombiningValueStateTag<InputT, AccumT, OutputT>) combiningTag).asBagTag();
  }

  private static class StructuredId implements Serializable {
    private final StateKind kind;
    private final String rawId;

    private StructuredId(String rawId) {
      this(StateKind.USER, rawId);
    }

    private StructuredId(StateKind kind, String rawId) {
      this.kind = kind;
      this.rawId = rawId;
    }

    public StructuredId asKind(StateKind kind) {
      return new StructuredId(kind, rawId);
    }

    public String getIdString() {
      return kind.prefix + rawId;
    }

    public void appendTo(Appendable sb) throws IOException {
      sb.append(kind.prefix).append(rawId);
    }

    public String getRawId() {
      return rawId;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(getClass())
          .add("id", rawId)
          .add("kind", kind)
          .toString();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }

      if (!(obj instanceof StructuredId)) {
        return false;
      }

      StructuredId that = (StructuredId) obj;
      return Objects.equals(this.kind, that.kind)
          && Objects.equals(this.rawId, that.rawId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(kind, rawId);
    }
  }

  private abstract static class StateTagBase<StateT extends State> implements StateTag<StateT> {

    protected final StructuredId id;

    protected StateTagBase(StructuredId id) {
      this.id = id;
    }

    @Override
    public String getId() {
      return id.getRawId();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(getClass())
          .add("id", id)
          .toString();
    }

    protected abstract StateTag<StateT> asKind(StateKind kind);

    public void appendTo(Appendable sb) throws IOException {
      id.appendTo(sb);
    }
  }

  /**
   * A value state cell for values of type {@code T}.
   *
   * @param <T> the type of value being stored
   */
  private static class ValueStateTag<T> extends StateTagBase<ValueState<T>> {

    private final Coder<T> coder;

    private ValueStateTag(StructuredId id, Coder<T> coder) {
      super(id);
      this.coder = coder;
    }

    @Override
    public ValueState<T> bind(StateBinder visitor) {
      return visitor.bindValue(this, coder);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }

      if (!(obj instanceof ValueStateTag)) {
        return false;
      }

      ValueStateTag<?> that = (ValueStateTag<?>) obj;
      return Objects.equals(this.id, that.id)
          && Objects.equals(this.coder, that.coder);
    }

    @Override
    public int hashCode() {
      return Objects.hash(getClass(), id, coder);
    }

    @Override
    protected StateTag<ValueState<T>> asKind(StateKind kind) {
      return new ValueStateTag<T>(id.asKind(kind), coder);
    }
  }

  /**
   * A general purpose state cell for values of type {@code T}.
   *
   * @param <InputT> the type of input values
   * @param <AccumT> type of mutable accumulator values
   * @param <OutputT> type of output values
   */
  private static class CombiningValueStateTag<InputT, AccumT, OutputT>
      extends StateTagBase<CombiningValueStateInternal<InputT, AccumT, OutputT>> {

    private final Coder<AccumT> accumCoder;
    private final CombineFn<InputT, AccumT, OutputT> combineFn;

    private CombiningValueStateTag(
        StructuredId id,
        Coder<AccumT> accumCoder, CombineFn<InputT, AccumT, OutputT> combineFn) {
      super(id);
      this.combineFn = combineFn;
      this.accumCoder = accumCoder;
    }

    @Override
    public CombiningValueStateInternal<InputT, AccumT, OutputT> bind(StateBinder visitor) {
      return visitor.bindCombiningValue(this, accumCoder, combineFn);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }

      if (!(obj instanceof CombiningValueStateTag)) {
        return false;
      }

      CombiningValueStateTag<?, ?, ?> that = (CombiningValueStateTag<?, ?, ?>) obj;
      return Objects.equals(this.id, that.id)
          && Objects.equals(this.accumCoder, that.accumCoder);
    }

    @Override
    public int hashCode() {
      return Objects.hash(getClass(), id, accumCoder);
    }

    @Override
    protected StateTag<CombiningValueStateInternal<InputT, AccumT, OutputT>> asKind(
        StateKind kind) {
      return new CombiningValueStateTag<>(id.asKind(kind), accumCoder, combineFn);
    }

    private StateTag<BagState<AccumT>> asBagTag() {
      return new BagStateTag<AccumT>(id, accumCoder);
    }
  }

  /**
   * A state cell optimized for bag-like access patterns (frequent additions, occasional reads
   * of all the values).
   *
   * @param <T> the type of value in the bag
   */
  private static class BagStateTag<T> extends StateTagBase<BagState<T>> {

    private final Coder<T> elemCoder;

    private BagStateTag(StructuredId id, Coder<T> elemCoder) {
      super(id);
      this.elemCoder = elemCoder;
    }

    @Override
    public BagState<T> bind(StateBinder visitor) {
      return visitor.bindBag(this, elemCoder);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }

      if (!(obj instanceof BagStateTag)) {
        return false;
      }

      BagStateTag<?> that = (BagStateTag<?>) obj;
      return Objects.equals(this.id, that.id)
          && Objects.equals(this.elemCoder, that.elemCoder);
    }

    @Override
    public int hashCode() {
      return Objects.hash(getClass(), id, elemCoder);
    }

    @Override
    protected StateTag<BagState<T>> asKind(StateKind kind) {
      return new BagStateTag<>(id.asKind(kind), elemCoder);
    }
  }

  private static class WatermarkStateTagInternal<W extends BoundedWindow>
      extends StateTagBase<WatermarkStateInternal> {

    /**
     * When multiple output times are added to hold the watermark, this determines how they are
     * combined, and also the behavior when merging windows. Does not contribute to equality/hash
     * since we have at most one watermark hold tag per computation.
     */
    private final OutputTimeFn<? super W> outputTimeFn;

    private WatermarkStateTagInternal(StructuredId id, OutputTimeFn<? super W> outputTimeFn) {
      super(id);
      this.outputTimeFn = outputTimeFn;
    }

    @Override
    public WatermarkStateInternal bind(StateBinder visitor) {
      return visitor.bindWatermark(this, outputTimeFn);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }

      if (!(obj instanceof WatermarkStateTagInternal)) {
        return false;
      }

      WatermarkStateTagInternal<?> that = (WatermarkStateTagInternal<?>) obj;
      return Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
      return Objects.hash(getClass(), id);
    }

    @Override
    protected StateTag<WatermarkStateInternal> asKind(StateKind kind) {
      return new WatermarkStateTagInternal<W>(id.asKind(kind), outputTimeFn);
    }
  }
}
