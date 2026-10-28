// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8;

/** Allocation fixture for the launcher test. This is not an R8 compiler. */
public class R8 {
  private static volatile Object sink;

  public static void main(String[] args) {
    byte[][] ring = new byte[16][];
    long checksum = 0;
    for (int index = 0; index < 16384; index++) {
      byte[] allocation = new byte[16384];
      allocation[0] = (byte) index;
      ring[index & 15] = allocation;
      sink = ring;
      checksum += allocation[0] & 255;
    }
    System.out.println("launcher-fixture-checksum=" + checksum);
  }
}
