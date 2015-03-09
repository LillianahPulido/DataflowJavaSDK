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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.PathMatcher;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Implements IOChannelFactory for local files.
 */
public class FileIOChannelFactory implements IOChannelFactory {
  private static final Logger LOG = LoggerFactory.getLogger(FileIOChannelFactory.class);

  // This implementation only allows for wildcards in the file name.
  // The directory portion must exist as-is.
  @Override
  public Collection<String> match(String spec) throws IOException {
    File file = new File(spec);

    File parent = file.getAbsoluteFile().getParentFile();
    if (!parent.exists()) {
      throw new IOException("Unable to find parent directory of " + spec);
    }

    // Method getAbsolutePath() on Windows platform may return something like
    // "c:\temp\file.txt". FileSystem.getPathMatcher() call below will treat
    // '\' (backslash) as an escape character, instead of a directory
    // separator. Replacing backslash with double-backslash solves the problem.
    // We perform the replacement on all platforms, even those that allow
    // backslash as a part of the filename, because Globs.toRegexPattern will
    // eat one backslash.
    String pathToMatch = file.getAbsolutePath().replaceAll(Matcher.quoteReplacement("\\"),
                                                           Matcher.quoteReplacement("\\\\"));

    final PathMatcher matcher =
        FileSystems.getDefault().getPathMatcher("glob:" + pathToMatch);
    File[] files = parent.listFiles(new FileFilter() {
      @Override
      public boolean accept(File pathname) {
        return matcher.matches(pathname.toPath());
      }
    });

    List<String> result = new LinkedList<>();
    for (File match : files) {
      result.add(match.getPath());
    }

    return result;
  }

  @Override
  public ReadableByteChannel open(String spec) throws IOException {
    LOG.debug("opening file {}", spec);
    FileInputStream inputStream = new FileInputStream(spec);
    return inputStream.getChannel();
  }

  @Override
  public WritableByteChannel create(String spec, String mimeType)
      throws IOException {
    LOG.debug("creating file {}", spec);
    return Channels.newChannel(
        new BufferedOutputStream(new FileOutputStream(spec)));
  }

  @Override
  public long getSizeBytes(String spec) throws IOException {
    return Files.size(FileSystems.getDefault().getPath(spec));
  }

  @Override
  public boolean isReadSeekEfficient(String spec) throws IOException {
    return true;
  }
}
