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

package com.google.cloud.dataflow.sdk.coders;

import com.google.cloud.dataflow.sdk.util.ExposedByteArrayOutputStream;
import com.google.cloud.dataflow.sdk.util.StreamUtils;
import com.google.cloud.dataflow.sdk.util.VarInt;
import com.google.cloud.dataflow.sdk.util.common.worker.PartialGroupByKeyOperation.StructuralByteArray;
import com.google.common.io.ByteStreams;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A {@link Coder} for {@code byte[]}.
 *
 * <p>The encoding format is as follows:
 * <ul>
 * <li>If in a non-nested context (the {@code byte[]} is the only value in the stream), the
 * bytes are read/written directly.</li>
 * <li>If in a nested context, the bytes are prefixed with the length of the array,
 * encoded via a {@link VarIntCoder}.</li>
 * </ul>
 */
public class ByteArrayCoder extends AtomicCoder<byte[]> {

  @JsonCreator
  public static ByteArrayCoder of() {
    return INSTANCE;
  }


  /////////////////////////////////////////////////////////////////////////////

  private static final ByteArrayCoder INSTANCE = new ByteArrayCoder();

  private ByteArrayCoder() {}

  @Override
  public void encode(byte[] value, OutputStream outStream, Context context)
      throws IOException, CoderException {
    if (value == null) {
      throw new CoderException("cannot encode a null byte[]");
    }
    if (!context.isWholeStream) {
      VarInt.encode(value.length, outStream);
      outStream.write(value);
    } else {
      outStream.write(value);
    }
  }

  /**
   * Encodes the provided {@code value} with the identical encoding to {@link #encode}, but with
   * optimizations that take ownership of the value.
   *
   * <p>Once passed to this method, {@code value} should never be observed or mutated again.
   */
  public void encodeAndOwn(byte[] value, OutputStream outStream, Context context)
      throws IOException, CoderException {
    if (!context.isWholeStream) {
      VarInt.encode(value.length, outStream);
      outStream.write(value);
    } else {
      if (outStream instanceof ExposedByteArrayOutputStream) {
        ((ExposedByteArrayOutputStream) outStream).writeAndOwn(value);
      } else {
        outStream.write(value);
      }
    }
  }

  @Override
  public byte[] decode(InputStream inStream, Context context)
      throws IOException, CoderException {
    if (context.isWholeStream) {
      return StreamUtils.getBytes(inStream);
    } else {
      int length = VarInt.decodeInt(inStream);
      if (length < 0) {
        throw new IOException("invalid length " + length);
      }
      byte[] value = new byte[length];
      ByteStreams.readFully(inStream, value);
      return value;
    }
  }

  /**
   * {@inheritDoc}
   *
   * @return objects that are equal if the two arrays contain the same bytes.
   */
  @Override
  public Object structuralValue(byte[] value) {
    return new StructuralByteArray(value);
  }

  /**
   * {@inheritDoc}
   *
   * @return {@code true} since {@link #getEncodedElementByteSize(byte[], Context)} runs in
   * constant time using the {@code length} of the provided array.
   */
  @Override
  public boolean isRegisterByteSizeObserverCheap(byte[] value, Context context) {
    return true;
  }

  @Override
  protected long getEncodedElementByteSize(byte[] value, Context context)
      throws Exception {
    if (value == null) {
      throw new CoderException("cannot encode a null byte[]");
    }
    long size = 0;
    if (!context.isWholeStream) {
      size += VarInt.getLength(value.length);
    }
    return size + value.length;
  }
}
