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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Respond to /threadz with the stack traces of all running threads.
 */
class ThreadzServlet extends BaseStatusServlet {

  public ThreadzServlet() {
    super("threadz");
  }

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setContentType("text/html;charset=utf-8");
    response.setStatus(HttpServletResponse.SC_OK);

    PrintWriter writer = response.getWriter();
    writer.println("<html>");

    Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
    for (Map.Entry<Thread,  StackTraceElement[]> entry : stacks.entrySet()) {
      Thread thread = entry.getKey();
      writer.println("Thread: " + thread + " State: " + thread.getState() + "<br>");
      for (StackTraceElement element : entry.getValue()) {
        writer.println("&nbsp&nbsp" + element + "<br>");
      }
      writer.println("<br>");
    }
    writer.println("</html>");
  }
}
