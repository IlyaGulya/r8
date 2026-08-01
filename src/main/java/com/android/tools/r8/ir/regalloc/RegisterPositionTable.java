// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import java.util.Arrays;

/** Position-only scratch table for register allocation. */
final class RegisterPositionTable {

  private static final int INITIAL_SIZE = 16;
  private static final int EPOCH_SHIFT = Integer.SIZE;
  private static final long POSITION_MASK = 0xffffffffL;
  private static final long EPOCH_MASK = ~POSITION_MASK;

  private long[] entries = new long[INITIAL_SIZE];
  private int epoch;
  private long epochBits;
  private int limit;

  RegisterPositionTable reset(int limit) {
    this.limit = limit;
    if (epoch == Integer.MAX_VALUE) {
      Arrays.fill(entries, 0);
      epoch = 1;
    } else {
      epoch++;
    }
    epochBits = (long) epoch << EPOCH_SHIFT;
    return this;
  }

  int get(int index) {
    if (index < entries.length) {
      long entry = entries[index];
      if ((entry & EPOCH_MASK) == epochBits) {
        return (int) entry;
      }
    }
    assert index < limit;
    return Integer.MAX_VALUE;
  }

  int get(int index, boolean isWide) {
    int result = get(index);
    return isWide ? Math.min(result, get(index + 1)) : result;
  }

  void set(int index, int position) {
    if (index >= entries.length) {
      grow(index + 1);
    }
    entries[index] = epochBits | (position & POSITION_MASK);
  }

  private void grow(int minSize) {
    int size = entries.length;
    while (size < minSize) {
      size *= 2;
    }
    size = Math.min(size, limit);
    entries = Arrays.copyOf(entries, size);
  }
}
