// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import com.android.tools.r8.errors.Unreachable;
import java.util.Arrays;
import java.util.BitSet;

public class RegisterPositionsImpl extends RegisterPositions {

  private static final int INITIAL_SIZE = 16;
  private static final int HOLDS_CONSTANT = 1;
  private static final int HOLDS_MONITOR = 1 << 1;
  private static final int HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING = 1 << 2;
  private static final int REGISTER_TYPE_MASK =
      HOLDS_CONSTANT | HOLDS_MONITOR | HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING;
  private final int limit;
  private int[] backing;
  private byte[] registerTypes;
  private final BitSet blockedRegisters;

  public RegisterPositionsImpl(int limit) {
    this.limit = limit;
    backing = new int[INITIAL_SIZE];
    for (int i = 0; i < INITIAL_SIZE; i++) {
      backing[i] = Integer.MAX_VALUE;
    }
    blockedRegisters = new BitSet(limit);
  }

  @Override
  public boolean hasType(int index, RegisterType type) {
    assert !isBlocked(index);
    int flags = getTypeFlags(index);
    switch (type) {
      case MONITOR:
        return (flags & HOLDS_MONITOR) != 0;
      case CONST_NUMBER:
        return (flags & HOLDS_CONSTANT) != 0;
      case OTHER:
        return (flags & REGISTER_TYPE_MASK) == 0;
      case ANY:
        return true;
      default:
        throw new Unreachable("Unexpected register position type: " + type);
    }
  }

  private int getTypeFlags(int index) {
    return registerTypes != null && index < registerTypes.length ? registerTypes[index] : 0;
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
    int flags = 0;
    if (intervals.isConstantNumberInterval()) {
      flags |= HOLDS_CONSTANT;
    }
    if (intervals.usedInMonitorOperation()) {
      flags |= HOLDS_MONITOR;
    }
    if (intervals.isNewStringInstanceDisallowingSpilling()) {
      flags |= HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING;
    }
    if (flags != 0) {
      ensureTypeFlags(index);
      registerTypes[index] = (byte) flags;
    } else if (registerTypes != null && index < registerTypes.length) {
      registerTypes[index] = 0;
    }
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
    blockedRegisters.set(index);
  }

  @Override
  public boolean isBlocked(int index) {
    return blockedRegisters.get(index);
  }

  private void ensureTypeFlags(int index) {
    if (registerTypes == null) {
      int size = INITIAL_SIZE;
      while (size <= index) {
        size *= 2;
      }
      registerTypes = new byte[size];
    } else if (index >= registerTypes.length) {
      int size = registerTypes.length;
      while (size <= index) {
        size *= 2;
      }
      registerTypes = Arrays.copyOf(registerTypes, size);
    }
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
