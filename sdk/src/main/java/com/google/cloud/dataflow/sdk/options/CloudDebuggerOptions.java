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

package com.google.cloud.dataflow.sdk.options;

import com.google.cloud.dataflow.sdk.annotations.Experimental;

/**
 * Options for controlling Cloud Debugger.
 */
@Description("[Experimental] Used to configure the Cloud Debugger")
@Experimental
public interface CloudDebuggerOptions {

  /**
   * User defined application version. Cloud Debugger uses it to group all
   * running debugged processes. Version should be different if users have
   * multiple parallel runs of the same application with different inputs.
   */
  @Description("User defined application version. Cloud Debugger uses it to group all "
      + "running debugged processes. cdbgVersion should be different if users have "
      + "multiple parallel runs of the same application with different inputs.")
  String getCdbgVersion();
  void setCdbgVersion(String value);

  /**
   * Return a JSON string for the Debugger metadata item.
   */
  public static class DebuggerConfig {
    private String version;
    public String getVersion() {
      return version;
    }
    public void setVersion(String version) {
      this.version = version;
    }
  }
}

