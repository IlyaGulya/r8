// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion.passes.result;

public final class IRCodeInvalidation {

  public static final int NONE = 0;
  public static final int INSTRUCTIONS = 1 << 0;
  public static final int SSA_VALUES = 1 << 1;
  public static final int CFG_TOPOLOGY = 1 << 2;
  public static final int REDUNDANT_BLOCKS = 1 << 3;
  public static final int CRITICAL_EDGES = 1 << 4;
  public static final int ALL =
      INSTRUCTIONS | SSA_VALUES | CFG_TOPOLOGY | REDUNDANT_BLOCKS | CRITICAL_EDGES;

  private IRCodeInvalidation() {}

  public static boolean isValid(int invalidations) {
    return (invalidations & ~ALL) == 0;
  }
}
