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
package com.google.cloud.dataflow.sdk.runners.worker;

import com.google.cloud.dataflow.sdk.runners.worker.status.BaseStatusServlet;
import com.google.cloud.dataflow.sdk.runners.worker.status.StatusDataProvider;
import com.google.cloud.dataflow.sdk.util.Weighted;
import com.google.cloud.dataflow.sdk.util.state.State;
import com.google.cloud.dataflow.sdk.util.state.StateNamespace;
import com.google.cloud.dataflow.sdk.util.state.StateTag;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.cache.Weigher;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Process-wide cache of per-key state.
 */
public class WindmillStateCache implements StatusDataProvider {

  private Cache<StateId, StateCacheEntry> stateCache;
  private int weight = 0;

  public WindmillStateCache() {
    final Weigher<Weighted, Weighted> weigher = Weighers.weightedKeysAndValues();

    stateCache =
        CacheBuilder.newBuilder()
        .maximumWeight(100000000 /* 100 MB */)
        .recordStats()
        .weigher(weigher)
        .removalListener(new RemovalListener<StateId, StateCacheEntry>() {
              @Override
              public void onRemoval(RemovalNotification<StateId, StateCacheEntry> removal) {
                if (removal.getCause() != RemovalCause.REPLACED) {
                  weight -= weigher.weigh(removal.getKey(), removal.getValue());
                }
              }
            })
        .build();
  }

  public long getWeight() {
    return weight;
  }

  /**
   * Per-computation view of the state cache.
   */
  public class ForComputation {
    private final String computation;
    private ForComputation(String computation) {
      this.computation = computation;
    }

    /**
     * Returns a per-computation, per-key view of the state cache.
     */
    public ForKey forKey(ByteString key, String stateFamily, long cacheToken) {
      return new ForKey(computation, key, stateFamily, cacheToken);
    }
  }

  /**
   * Per-computation, per-key view of the state cache.
   */
  public class ForKey {
    private final String computation;
    private final ByteString key;
    private final String stateFamily;
    private final long cacheToken;

    private ForKey(String computation, ByteString key, String stateFamily, long cacheToken) {
      this.computation = computation;
      this.key = key;
      this.stateFamily = stateFamily;
      this.cacheToken = cacheToken;
    }

    public <T extends State> T get(StateNamespace namespace, StateTag<T> address) {
      return WindmillStateCache.this.get(
          computation, key, stateFamily, cacheToken, namespace, address);
    }

    public <T extends State> void put(
        StateNamespace namespace, StateTag<T> address, T value, long weight) {
      WindmillStateCache.this.put(
          computation, key, stateFamily, cacheToken, namespace, address, value, weight);
    }
  }

  /**
   * Returns a per-computation view of the state cache.
   */
  public ForComputation forComputation(String computation) {
    return new ForComputation(computation);
  }

  private <T extends State> T get(String computation, ByteString processingKey, String stateFamily,
      long token, StateNamespace namespace, StateTag<T> address) {
    StateId id = new StateId(computation, processingKey, stateFamily, namespace);
    StateCacheEntry entry = stateCache.getIfPresent(id);
    if (entry == null) {
      return null;
    }
    if (entry.getToken() != token) {
      stateCache.invalidate(id);
      return null;
    }
    return entry.get(namespace, address);
  }

  private <T extends State> void put(String computation, ByteString processingKey,
      String stateFamily, long token, StateNamespace namespace, StateTag<T> address, T value,
      long weight) {
    StateId id = new StateId(computation, processingKey, stateFamily, namespace);
    StateCacheEntry entry = stateCache.getIfPresent(id);
    if (entry == null || entry.getToken() != token) {
      entry = new StateCacheEntry(token);
      this.weight += id.getWeight();
    }
    this.weight += entry.put(namespace, address, value, weight);
    // Always add back to the cache to update the weight.
    stateCache.put(id, entry);
  }

  /**
   * Struct identifying a cache entry that contains all data for a key and namespace.
   */
  private static class StateId implements Weighted {
    public final String computation;
    public final ByteString processingKey;
    public final String stateFamily;
    public final Object namespaceKey;

    public StateId(String computation, ByteString processingKey, String stateFamily,
        StateNamespace namespace) {
      this.computation = computation;
      this.processingKey = processingKey;
      this.stateFamily = stateFamily;
      this.namespaceKey = namespace.getCacheKey();
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof StateId) {
        StateId otherId = (StateId) other;
        return computation.equals(otherId.computation)
            && processingKey.equals(otherId.processingKey)
            && stateFamily.equals(otherId.stateFamily)
            && namespaceKey.equals(otherId.namespaceKey);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(computation, processingKey, namespaceKey);
    }

    @Override
    public long getWeight() {
      return processingKey.size();
    }
  }

  /**
   * Entry in the state cache that stores a map of values and a token representing the
   * validity of the values.
   */
  private static class StateCacheEntry implements Weighted {
    private final long token;
    private final Map<NamespacedTag<?>, WeightedValue<?>> values;
    private long weight;

    public StateCacheEntry(long token) {
      this.values = new HashMap<>();
      this.token = token;
      this.weight = 0;
    }

    @SuppressWarnings("unchecked")
    public <T extends State> T get(StateNamespace namespace, StateTag<T> tag) {
      WeightedValue<T> weightedValue =
          (WeightedValue<T>) values.get(new NamespacedTag(namespace, tag));
      return weightedValue == null ? null : weightedValue.value;
    }

    public <T extends State> long put(
        StateNamespace namespace, StateTag<T> tag, T value, long weight) {
      WeightedValue<T> weightedValue =
          (WeightedValue<T>) values.get(new NamespacedTag(namespace, tag));
      long weightDelta = 0;
      if (weightedValue == null) {
        weightedValue = new WeightedValue<T>();
      } else {
        weightDelta -= weightedValue.weight;
      }
      weightedValue.value = value;
      weightedValue.weight = weight;
      weightDelta += weight;
      this.weight += weightDelta;
      values.put(new NamespacedTag(namespace, tag), weightedValue);
      return weightDelta;
    }

    @Override
    public long getWeight() {
      return weight;
    }

    public long getToken() {
      return token;
    }

    private static class NamespacedTag<T extends State> {
      private final StateNamespace namespace;
      private final StateTag<T> tag;
      NamespacedTag(StateNamespace namespace, StateTag<T> tag) {
        this.namespace = namespace;
        this.tag = tag;
      }

      @Override
      public boolean equals(Object other) {
        if (!(other instanceof NamespacedTag)) {
          return false;
        }
        NamespacedTag<?> that = (NamespacedTag<?>) other;
        return namespace.equals(that.namespace) && tag.equals(that.tag);
      }

      @Override
      public int hashCode() {
        return Objects.hash(namespace, tag);
      }
    }

    private static class WeightedValue<T> {
      public long weight = 0;
      public T value = null;
    }
  }

  /**
   * Print summary statistics of the cache to the given {@link PrintWriter}.
   */
  @Override
  public void appendSummaryHtml(PrintWriter response) {
    response.println("Cache Stats: <br><table border=0>");
    response.println(
        "<tr><th>Hit Ratio</th><th>Evictions</th><th>Size</th><th>Weight</th></tr><tr>");
    response.println("<th>" + stateCache.stats().hitRate() + "</th>");
    response.println("<th>" + stateCache.stats().evictionCount() + "</th>");
    response.println("<th>" + stateCache.size() + "</th>");
    response.println("<th>" + getWeight() + "</th>");
    response.println("</tr></table><br>");
  }


  public BaseStatusServlet statusServlet() {
    return new BaseStatusServlet("/cachez") {
      @Override
      protected void doGet(HttpServletRequest request, HttpServletResponse response)
          throws IOException, ServletException {

        PrintWriter writer = response.getWriter();
        writer.println("<h1>Cache Information</h1>");
        appendSummaryHtml(writer);
      }
    };
  }


}
