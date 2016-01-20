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
package com.google.cloud.dataflow.sdk.runners.worker.status;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link WorkerStatusPages}.
 */
@RunWith(JUnit4.class)
public class WorkerStatusPagesTest {

  private final Server server = new Server();
  private final LocalConnector connector = new LocalConnector(server);
  private final WorkerStatusPages wsp = new WorkerStatusPages(server);

  @Before
  public void setUp() throws Exception {
    server.addConnector(connector);
    wsp.start();
  }

  @After
  public void tearDown() throws Exception {
    wsp.stop();
  }

  @Test
  public void testThreadz() throws Exception {
    String response = getPage("/threadz");
    assertThat(response, containsString("HTTP/1.1 200 OK"));
    assertThat("Test method should appear in stack trace",
        response, containsString("WorkerStatusPagesTest.testThreadz"));
  }

  @Test
  public void testHealthz() throws Exception {
    String response = getPage("/threadz");
    assertThat(response, containsString("HTTP/1.1 200 OK"));
    assertThat(response, containsString("ok"));
  }

  @Test
  public void testUnknownHandler() throws Exception {
    String response = getPage("/missinghandlerz");
    assertThat(response, containsString("HTTP/1.1 302 Found"));
    assertThat(response, containsString("Location: http://localhost/statusz"));
  }

  private String getPage(String requestURL) throws Exception {
    String request = String.format("GET %s HTTP/1.1\nhost: localhost\n\n", requestURL);
    return connector.getResponses(request);
  }
}
