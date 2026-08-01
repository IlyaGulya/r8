// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import java.util.Arrays;

/** Position-only scratch table for register allocation. */
final class RegisterPositionTable {

  private static final int INITIAL_SIZE = 16;

  private int[] positions = new int[INITIAL_SIZE];
  private int[] touchedIndices = new int[0];
  private int touchedSize;
  private int limit;

  RegisterPositionTable() {
    Arrays.fill(positions, Integer.MAX_VALUE);
  }

  RegisterPositionTable reset(int limit) {
    this.limit = limit;
    for (int i = 0; i < touchedSize; i++) {
      positions[touchedIndices[i]] = Integer.MAX_VALUE;
    }
    touchedSize = 0;
    return this;
  }

  int get(int index) {
    if (index < positions.length) {
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
    if (positions[index] == Integer.MAX_VALUE) {
      ensureTouchedCapacity();
      touchedIndices[touchedSize++] = index;
    }
    positions[index] = position;
  }

  private void ensureTouchedCapacity() {
    if (touchedSize == touchedIndices.length) {
      int size = Math.max(INITIAL_SIZE, touchedSize * 2);
      touchedIndices = Arrays.copyOf(touchedIndices, size);
    }
  }

  private void grow(int minSize) {
    int size = positions.length;
    while (size < minSize) {
      size *= 2;
    }
    size = Math.min(size, limit);
    int oldSize = positions.length;
    positions = Arrays.copyOf(positions, size);
    Arrays.fill(positions, oldSize, size, Integer.MAX_VALUE);
  }
}
