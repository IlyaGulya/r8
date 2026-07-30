// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import com.android.tools.r8.errors.Unreachable;
import java.util.Arrays;

public class RegisterPositionsImpl extends RegisterPositions {

  private static final int INITIAL_SIZE = 16;
  private static final int HOLDS_CONSTANT = 1;
  private static final int HOLDS_MONITOR = 1 << 1;
  private static final int HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING = 1 << 2;
  private static final int REGISTER_TYPE_MASK =
      HOLDS_CONSTANT | HOLDS_MONITOR | HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING;
  private static final int BLOCKED = 1 << 3;
  private final int limit;
  private int[] backing;
  private final byte[] registerFlags;

  public RegisterPositionsImpl(int limit) {
    this.limit = limit;
    backing = new int[INITIAL_SIZE];
    for (int i = 0; i < INITIAL_SIZE; i++) {
      backing[i] = Integer.MAX_VALUE;
    }
    // Wide register queries may inspect the register immediately after the limit.
    registerFlags = new byte[limit + 1];
  }

  @Override
  public boolean hasType(int index, RegisterType type) {
    assert !isBlocked(index);
    switch (type) {
      case MONITOR:
        return (registerFlags[index] & HOLDS_MONITOR) != 0;
      case CONST_NUMBER:
        return (registerFlags[index] & HOLDS_CONSTANT) != 0;
      case OTHER:
        return (registerFlags[index] & REGISTER_TYPE_MASK) == 0;
      case ANY:
        return true;
      default:
        throw new Unreachable("Unexpected register position type: " + type);
    }
  }

  private void set(int index, int value) {
    if (index >= backing.length) {
      grow(index + 1);
    }
    backing[index] = value;
  }

  @Override
  public void set(int index, int value, LiveIntervals intervals) {
    set(index, value);
    int flags = registerFlags[index] & BLOCKED;
    if (intervals.isConstantNumberInterval()) {
      flags |= HOLDS_CONSTANT;
    }
    if (intervals.usedInMonitorOperation()) {
      flags |= HOLDS_MONITOR;
    }
    if (intervals.isNewStringInstanceDisallowingSpilling()) {
      flags |= HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING;
    }
    registerFlags[index] = (byte) flags;
  }

  @Override
  public int get(int index) {
    assert !isBlocked(index);
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
    registerFlags[index] |= BLOCKED;
  }

  @Override
  public boolean isBlocked(int index) {
    return (registerFlags[index] & BLOCKED) != 0;
  }

  private void grow(int minSize) {
    int size = backing.length;
    while (size < minSize) {
      size *= 2;
    }
    size = Math.min(size, limit);
    int oldSize = backing.length;
    backing = Arrays.copyOf(backing, size);
    for (int i = oldSize; i < size; i++) {
      backing[i] = Integer.MAX_VALUE;
    }
  }
}
