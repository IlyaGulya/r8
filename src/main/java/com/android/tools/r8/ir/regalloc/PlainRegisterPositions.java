// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import com.android.tools.r8.errors.Unreachable;
import java.util.Arrays;

/** A position-only register table without type or blocked-register state. */
class PlainRegisterPositions extends RegisterPositions {

  private static final int INITIAL_SIZE = 16;

  private final int limit;
  private int[] backing;

  PlainRegisterPositions(int limit) {
    this.limit = limit;
    backing = new int[INITIAL_SIZE];
    Arrays.fill(backing, Integer.MAX_VALUE);
  }

  @Override
  public boolean hasType(int index, RegisterType type) {
    throw unsupportedOperation();
  }

  @Override
  public void set(int index, int value, LiveIntervals intervals) {
    if (index >= backing.length) {
      grow(index + 1);
    }
    backing[index] = value;
  }

  @Override
  public int get(int index) {
    if (index < backing.length) {
      return backing[index];
    }
    assert index < limit;
    return Integer.MAX_VALUE;
  }

  @Override
  public int getLimit() {
    return limit;
  }

  @Override
  public void setBlocked(int index) {
    throw unsupportedOperation();
  }

  @Override
  public boolean isBlocked(int index) {
    throw unsupportedOperation();
  }

  private void grow(int minSize) {
    int size = backing.length;
    while (size < minSize) {
      size *= 2;
    }
    size = Math.min(size, limit);
    int oldSize = backing.length;
    backing = Arrays.copyOf(backing, size);
    Arrays.fill(backing, oldSize, size, Integer.MAX_VALUE);
  }

  private Unreachable unsupportedOperation() {
    return new Unreachable("Operation requires register type or blocked-register state");
  }
}
