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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Manages the server providing the worker status pages.
 */
public class WorkerStatusPages {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerStatusPages.class);

  private static final int DEFAULT_STATUS_PORT = 8081;

  private final Server statusServer;
  private final ServletHandler servletHandler = new ServletHandler();

  private WorkerStatusPages(int statusPort) {
    this.statusServer = new Server(statusPort);
    this.statusServer.setHandler(servletHandler);

    // Install the default servlets (threadz, healthz, heapz)
    addPage(new ThreadzServlet());
    addPage(new HealthzServlet());
    addPage(new HeapzServlet());
  }

  public static WorkerStatusPages create() {
    int statusPort = DEFAULT_STATUS_PORT;
    if (System.getProperties().containsKey("status_port")) {
      statusPort = Integer.parseInt(System.getProperty("status_port"));
    }
    return new WorkerStatusPages(statusPort);
  }

  /** Start the server. */
  public void start() {
    if (statusServer.isStarted()) {
      LOG.warn("Status server already started on port {}", statusServer.getURI().getPort());
      return;
    }

    try {
      addPage(new RedirectToStatusz404Handler());
      statusServer.start();

      LOG.info("Status server started on port {}", statusServer.getURI().getPort());
      statusServer.join();
    } catch (Exception e) {
      LOG.warn("Status server failed to start: ", e);
    }
  }

  /** Stop the server. */
  public void stop() {
    try {
      statusServer.stop();
    } catch (Exception e) {
      LOG.warn("Status server failed to stop: ", e);
    }
  }

  /**
   * Add a status servlet.
   */
  public void addPage(BaseStatusServlet servlet) {
    ServletHolder holder = new ServletHolder();
    holder.setServlet(servlet);
    servletHandler.addServletWithMapping(holder, servlet.getPath());
  }

  /**
   * Redirect missing pages to /statusz.
   */
  private static class RedirectToStatusz404Handler extends BaseStatusServlet {

    protected RedirectToStatusz404Handler() {
      super("/*");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      resp.setContentType("text/html;charset=utf-8");
      resp.setStatus(HttpServletResponse.SC_NOT_FOUND);

      PrintWriter writer = resp.getWriter();
      writer.println("<html>");
      writer.println("404 Not Found. Try <a href=\"/statusz\">/statusz</a>");
      writer.println("</html>");
    }
  }
}
