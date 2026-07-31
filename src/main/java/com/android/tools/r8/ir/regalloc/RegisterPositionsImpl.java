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
  private static final int BITS_PER_REGISTER = 4;
  private static final int INLINE_REGISTER_COUNT = Long.SIZE / BITS_PER_REGISTER;
  private static final long REGISTER_FLAGS_MASK = (1L << BITS_PER_REGISTER) - 1;
  private final int limit;
  private int[] backing;
  private long inlineRegisterFlags;
  private byte[] overflowRegisterFlags;

  public RegisterPositionsImpl(int limit) {
    this.limit = limit;
    backing = new int[INITIAL_SIZE];
    for (int i = 0; i < INITIAL_SIZE; i++) {
      backing[i] = Integer.MAX_VALUE;
    }
  }

  @Override
  public boolean hasType(int index, RegisterType type) {
    assert !isBlocked(index);
    int flags = getFlags(index);
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

  private int getFlags(int index) {
    if (index < INLINE_REGISTER_COUNT) {
      return (int) ((inlineRegisterFlags >>> (index * BITS_PER_REGISTER)) & REGISTER_FLAGS_MASK);
    }
    return getOverflowFlags(index - INLINE_REGISTER_COUNT);
  }

  private int getOverflowFlags(int overflowIndex) {
    return overflowRegisterFlags != null && overflowIndex < overflowRegisterFlags.length
        ? overflowRegisterFlags[overflowIndex]
        : 0;
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
    int flags = getFlags(index) & BLOCKED;
    if (intervals.isConstantNumberInterval()) {
      flags |= HOLDS_CONSTANT;
    }
    if (intervals.usedInMonitorOperation()) {
      flags |= HOLDS_MONITOR;
    }
    if (intervals.isNewStringInstanceDisallowingSpilling()) {
      flags |= HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING;
    }
    setFlags(index, flags);
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
    if (index < INLINE_REGISTER_COUNT) {
      inlineRegisterFlags = setInlineBlocked(inlineRegisterFlags, index);
    } else {
      setOverflowBlocked(index - INLINE_REGISTER_COUNT);
    }
  }

  private static long setInlineBlocked(long flags, int index) {
    return ((long) BLOCKED << (index * BITS_PER_REGISTER)) | flags;
  }

  private void setOverflowBlocked(int overflowIndex) {
    setOverflowFlags(overflowIndex, getOverflowFlags(overflowIndex) | BLOCKED);
  }

  @Override
  public boolean isBlocked(int index) {
    return (getFlags(index) & BLOCKED) != 0;
  }

  private void setFlags(int index, int flags) {
    if (index < INLINE_REGISTER_COUNT) {
      int shift = index * BITS_PER_REGISTER;
      inlineRegisterFlags =
          (inlineRegisterFlags & ~(REGISTER_FLAGS_MASK << shift))
              | (((long) flags & REGISTER_FLAGS_MASK) << shift);
      return;
    }
    setOverflowFlags(index - INLINE_REGISTER_COUNT, flags);
  }

  private void setOverflowFlags(int overflowIndex, int flags) {
    if (overflowRegisterFlags == null) {
      overflowRegisterFlags = new byte[Math.max(INITIAL_SIZE, overflowIndex + 1)];
    } else if (overflowIndex >= overflowRegisterFlags.length) {
      overflowRegisterFlags =
          Arrays.copyOf(
              overflowRegisterFlags,
              Math.max(overflowIndex + 1, overflowRegisterFlags.length * 2));
    }
    overflowRegisterFlags[overflowIndex] = (byte) flags;
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
