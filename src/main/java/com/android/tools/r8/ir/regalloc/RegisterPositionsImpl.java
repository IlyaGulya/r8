// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import com.android.tools.r8.errors.Unreachable;
import java.util.Arrays;

public class RegisterPositionsImpl extends RegisterPositions {

  private static final int INITIAL_SIZE = 16;
  private static final long POSITION_MASK = 0xffffffffL;
  private static final long HAS_POSITION = 1L << 32;
  private static final long HOLDS_CONSTANT = 1L << 33;
  private static final long HOLDS_MONITOR = 1L << 34;
  private static final long HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING = 1L << 35;
  private static final long REGISTER_TYPE_MASK =
      HOLDS_CONSTANT | HOLDS_MONITOR | HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING;
  private static final long BLOCKED = 1L << 36;
  private final int limit;
  private long[] registerState;

  public RegisterPositionsImpl(int limit) {
    this.limit = limit;
    // Wide register queries may inspect the register immediately after the limit.
    registerState = new long[Math.min(INITIAL_SIZE, limit + 1)];
  }

  @Override
  public boolean hasType(int index, RegisterType type) {
    assert !isBlocked(index);
    long state = getState(index);
    switch (type) {
      case MONITOR:
        return (state & HOLDS_MONITOR) != 0;
      case CONST_NUMBER:
        return (state & HOLDS_CONSTANT) != 0;
      case OTHER:
        return (state & REGISTER_TYPE_MASK) == 0;
      case ANY:
        return true;
      default:
        throw new Unreachable("Unexpected register position type: " + type);
    }
  }

  private long getState(int index) {
    return index < registerState.length ? registerState[index] : 0;
  }

  private void set(int index, int value) {
    ensureCapacity(index);
    registerState[index] =
        (registerState[index] & ~POSITION_MASK) | HAS_POSITION | (value & POSITION_MASK);
  }

  @Override
  public void set(int index, int value, LiveIntervals intervals) {
    set(index, value);
    long typeFlags = 0;
    if (intervals.isConstantNumberInterval()) {
      typeFlags |= HOLDS_CONSTANT;
    }
    if (intervals.usedInMonitorOperation()) {
      typeFlags |= HOLDS_MONITOR;
    }
    if (intervals.isNewStringInstanceDisallowingSpilling()) {
      typeFlags |= HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING;
    }
    registerState[index] = (registerState[index] & ~REGISTER_TYPE_MASK) | typeFlags;
  }

  @Override
  public int get(int index) {
    assert !isBlocked(index);
    long state = getState(index);
    if ((state & HAS_POSITION) != 0) {
      return (int) state;
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
    ensureCapacity(index);
    registerState[index] |= BLOCKED;
  }

  @Override
  public boolean isBlocked(int index) {
    return (getState(index) & BLOCKED) != 0;
  }

  private void ensureCapacity(int index) {
    if (index < registerState.length) {
      return;
    }
    int size = registerState.length;
    while (size <= index) {
      size *= 2;
    }
    registerState = Arrays.copyOf(registerState, Math.min(size, limit + 1));
  }
}
