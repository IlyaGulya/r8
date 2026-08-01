// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import java.util.Arrays;

/** Position-only scratch table for register allocation. */
final class RegisterPositionTable {

  private static final int INITIAL_SIZE = 16;
  private static final int MAX_EPOCH = 0xff;

  private int[] positions = new int[INITIAL_SIZE];
  private byte[] epochs = new byte[INITIAL_SIZE];
  private int epoch;
  private int limit;

  RegisterPositionTable reset(int limit) {
    this.limit = limit;
    if (epoch == MAX_EPOCH) {
      Arrays.fill(epochs, (byte) 0);
      epoch = 1;
    } else {
      epoch++;
    }
    return this;
  }

  int get(int index) {
    if (index < positions.length && epochs[index] == (byte) epoch) {
      return positions[index];
    }
    assert index < limit;
    return Integer.MAX_VALUE;
  }

  int get(int index, boolean isWide) {
    int result = get(index);
    return isWide ? Math.min(result, get(index + 1)) : result;
  }

  void set(int index, int position) {
    if (index >= positions.length) {
      grow(index + 1);
    }
    positions[index] = position;
    epochs[index] = (byte) epoch;
  }

  private void grow(int minSize) {
    int size = positions.length;
    while (size < minSize) {
      size *= 2;
    }
    size = Math.min(size, limit);
    positions = Arrays.copyOf(positions, size);
    epochs = Arrays.copyOf(epochs, size);
  }
}
