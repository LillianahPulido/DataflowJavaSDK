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

import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.util.Throwables;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.Objects;
import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.dataflow.sdk.options.GcsOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.util.gcsfs.GcsPath;
import com.google.cloud.dataflow.sdk.util.gcsio.GoogleCloudStorageReadChannel;
import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Test case for {@link GcsUtil}. */
@RunWith(JUnit4.class)
public class GcsUtilTest {
  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void testGlobTranslation() {
    assertEquals("foo", GcsUtil.globToRegexp("foo"));
    assertEquals("fo[^/]*o", GcsUtil.globToRegexp("fo*o"));
    assertEquals("f[^/]*o\\.[^/]", GcsUtil.globToRegexp("f*o.?"));
    assertEquals("foo-[0-9][^/]*", GcsUtil.globToRegexp("foo-[0-9]*"));
  }

  @Test
  public void testCreationWithDefaultOptions() {
    GcsOptions pipelineOptions = PipelineOptionsFactory.as(GcsOptions.class);
    pipelineOptions.setGcpCredential(Mockito.mock(Credential.class));
    assertNotNull(pipelineOptions.getGcpCredential());
  }

  @Test
  public void testCreationWithExecutorServiceProvided() {
    GcsOptions pipelineOptions = PipelineOptionsFactory.as(GcsOptions.class);
    pipelineOptions.setGcpCredential(Mockito.mock(Credential.class));
    pipelineOptions.setExecutorService(Executors.newCachedThreadPool());
    assertSame(pipelineOptions.getExecutorService(), pipelineOptions.getGcsUtil().executorService);
  }

  @Test
  public void testCreationWithGcsUtilProvided() {
    GcsOptions pipelineOptions = PipelineOptionsFactory.as(GcsOptions.class);
    GcsUtil gcsUtil = Mockito.mock(GcsUtil.class);
    pipelineOptions.setGcsUtil(gcsUtil);
    assertSame(gcsUtil, pipelineOptions.getGcsUtil());
  }

  @Test
  public void testMultipleThreadsCanCompleteOutOfOrderWithDefaultThreadPool() throws Exception {
    GcsOptions pipelineOptions = PipelineOptionsFactory.as(GcsOptions.class);
    ExecutorService executorService = pipelineOptions.getExecutorService();

    int numThreads = 1000;
    final CountDownLatch[] countDownLatches = new CountDownLatch[numThreads];
    for (int i = 0; i < numThreads; i++) {
      final int currentLatch = i;
      countDownLatches[i] = new CountDownLatch(1);
      executorService.execute(new Runnable() {
        @Override
        public void run() {
          // Wait for latch N and then release latch N - 1
          try {
            countDownLatches[currentLatch].await();
            if (currentLatch > 0) {
              countDownLatches[currentLatch - 1].countDown();
            }
          } catch (InterruptedException e) {
            throw Throwables.propagate(e);
          }
        }
      });
    }

    // Release the last latch starting the chain reaction.
    countDownLatches[countDownLatches.length - 1].countDown();
    executorService.shutdown();
    assertTrue("Expected tasks to complete",
        executorService.awaitTermination(10, TimeUnit.SECONDS));
  }

  @Test
  public void testGlobExpansion() throws IOException {
    GcsOptions pipelineOptions = PipelineOptionsFactory.as(GcsOptions.class);
    pipelineOptions.setGcpCredential(Mockito.mock(Credential.class));
    GcsUtil gcsUtil = pipelineOptions.getGcsUtil();

    Storage mockStorage = Mockito.mock(Storage.class);
    gcsUtil.setStorageClient(mockStorage);

    Storage.Objects mockStorageObjects = Mockito.mock(Storage.Objects.class);
    Storage.Objects.List mockStorageList = Mockito.mock(Storage.Objects.List.class);

    Objects modelObjects = new Objects();
    List<StorageObject> items = new ArrayList<>();
    // A directory
    items.add(new StorageObject().setBucket("testbucket").setName("testdirectory/"));

    // Files within the directory
    items.add(new StorageObject().setBucket("testbucket").setName("testdirectory/file1name"));
    items.add(new StorageObject().setBucket("testbucket").setName("testdirectory/file2name"));
    items.add(new StorageObject().setBucket("testbucket").setName("testdirectory/file3name"));
    items.add(new StorageObject().setBucket("testbucket").setName("testdirectory/otherfile"));
    items.add(new StorageObject().setBucket("testbucket").setName("testdirectory/anotherfile"));

    modelObjects.setItems(items);

    when(mockStorage.objects()).thenReturn(mockStorageObjects);
    when(mockStorageObjects.list("testbucket")).thenReturn(mockStorageList);
    when(mockStorageList.execute()).thenReturn(modelObjects);

    // Test a single file.
    {
      GcsPath pattern = GcsPath.fromUri("gs://testbucket/testdirectory/otherfile");
      List<GcsPath> expectedFiles =
          ImmutableList.of(GcsPath.fromUri("gs://testbucket/testdirectory/otherfile"));

      assertThat(expectedFiles, contains(gcsUtil.expand(pattern).toArray()));
    }

    // Test patterns.
    {
      GcsPath pattern = GcsPath.fromUri("gs://testbucket/testdirectory/file*");
      List<GcsPath> expectedFiles = ImmutableList.of(
          GcsPath.fromUri("gs://testbucket/testdirectory/file1name"),
          GcsPath.fromUri("gs://testbucket/testdirectory/file2name"),
          GcsPath.fromUri("gs://testbucket/testdirectory/file3name"));

      assertThat(expectedFiles, contains(gcsUtil.expand(pattern).toArray()));
    }

    {
      GcsPath pattern = GcsPath.fromUri("gs://testbucket/testdirectory/file[1-3]*");
      List<GcsPath> expectedFiles = ImmutableList.of(
          GcsPath.fromUri("gs://testbucket/testdirectory/file1name"),
          GcsPath.fromUri("gs://testbucket/testdirectory/file2name"),
          GcsPath.fromUri("gs://testbucket/testdirectory/file3name"));

      assertThat(expectedFiles, contains(gcsUtil.expand(pattern).toArray()));
    }

    {
      GcsPath pattern = GcsPath.fromUri("gs://testbucket/testdirectory/file?name");
      List<GcsPath> expectedFiles = ImmutableList.of(
          GcsPath.fromUri("gs://testbucket/testdirectory/file1name"),
          GcsPath.fromUri("gs://testbucket/testdirectory/file2name"),
          GcsPath.fromUri("gs://testbucket/testdirectory/file3name"));

      assertThat(expectedFiles, contains(gcsUtil.expand(pattern).toArray()));
    }

    {
      GcsPath pattern = GcsPath.fromUri("gs://testbucket/test*ectory/fi*name");
      List<GcsPath> expectedFiles = ImmutableList.of(
          GcsPath.fromUri("gs://testbucket/testdirectory/file1name"),
          GcsPath.fromUri("gs://testbucket/testdirectory/file2name"),
          GcsPath.fromUri("gs://testbucket/testdirectory/file3name"));

      assertThat(expectedFiles, contains(gcsUtil.expand(pattern).toArray()));
    }
  }

  // Patterns that contain recursive wildcards ('**') are not supported.
  @Test
  public void testRecursiveGlobExpansionFails() throws IOException {
    GcsOptions pipelineOptions = PipelineOptionsFactory.as(GcsOptions.class);
    pipelineOptions.setGcpCredential(Mockito.mock(Credential.class));
    GcsUtil gcsUtil = pipelineOptions.getGcsUtil();
    GcsPath pattern = GcsPath.fromUri("gs://testbucket/test**");

    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("Unsupported wildcard usage");
    gcsUtil.expand(pattern);
  }

  // GCSUtil.expand() should not fail for non-existent single files or directories, since GCS file
  // listing is only eventually consistent.
  @Test
  public void testNonExistent() throws IOException {
    GcsOptions pipelineOptions = PipelineOptionsFactory.as(GcsOptions.class);
    pipelineOptions.setGcpCredential(Mockito.mock(Credential.class));
    GcsUtil gcsUtil = pipelineOptions.getGcsUtil();

    Storage mockStorage = Mockito.mock(Storage.class);
    gcsUtil.setStorageClient(mockStorage);

    Storage.Objects mockStorageObjects = Mockito.mock(Storage.Objects.class);
    Storage.Objects.List mockStorageList = Mockito.mock(Storage.Objects.List.class);

    Objects modelObjects = new Objects();
    List<StorageObject> items = new ArrayList<>();

    // A directory
    items.add(new StorageObject().setBucket("testbucket").setName("testdirectory/"));
    modelObjects.setItems(items);

    when(mockStorage.objects()).thenReturn(mockStorageObjects);
    when(mockStorageObjects.list("testbucket")).thenReturn(mockStorageList);
    when(mockStorageList.execute()).thenReturn(modelObjects);

    {
      GcsPath pattern = GcsPath.fromUri("gs://testbucket/testdirectory/nonexistentfile");
      List<GcsPath> expectedFiles =
          ImmutableList.of(GcsPath.fromUri("gs://testbucket/testdirectory/nonexistentfile"));

      assertThat(expectedFiles, contains(gcsUtil.expand(pattern).toArray()));
    }

    {
      GcsPath pattern = GcsPath.fromUri("gs://testbucket/testdirectory/nonexistentdirectory/");
      List<GcsPath> expectedFiles =
          ImmutableList.of(GcsPath.fromUri("gs://testbucket/testdirectory/nonexistentdirectory/"));

      assertThat(expectedFiles, contains(gcsUtil.expand(pattern).toArray()));
    }
  }

  @Test
  public void testGCSChannelCloseIdempotent() throws IOException {
    SeekableByteChannel channel =
        new GoogleCloudStorageReadChannel(null, "dummybucket", "dummyobject", null);
    channel.close();
    channel.close();
  }
}
