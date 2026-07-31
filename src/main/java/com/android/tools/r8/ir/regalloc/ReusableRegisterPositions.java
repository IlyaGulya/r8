// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import com.android.tools.r8.errors.Unreachable;
import java.util.Arrays;

/** Allocator-owned register positions that are reset by advancing an entry epoch. */
final class ReusableRegisterPositions extends RegisterPositions {

  private static final int INITIAL_SIZE = 16;
  private static final int HOLDS_CONSTANT = 1;
  private static final int HOLDS_MONITOR = 1 << 1;
  private static final int HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING = 1 << 2;
  private static final int REGISTER_TYPE_MASK =
      HOLDS_CONSTANT | HOLDS_MONITOR | HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING;
  private static final int BLOCKED = 1 << 3;

  private static final int FLAGS_SHIFT = Integer.SIZE;
  private static final int EPOCH_SHIFT = FLAGS_SHIFT + 4;
  private static final int MAX_EPOCH = (1 << (Long.SIZE - EPOCH_SHIFT)) - 1;
  private static final long POSITION_MASK = 0xffffffffL;
  private static final long FLAGS_MASK = 0xfL << FLAGS_SHIFT;
  private static final long EPOCH_MASK = ~((1L << EPOCH_SHIFT) - 1);
  private static final long DEFAULT_POSITION = Integer.MAX_VALUE;

  private long[] entries = new long[INITIAL_SIZE];
  private int epoch;
  private long epochBits;
  private int limit;

  ReusableRegisterPositions() {
    reset(0);
  }

  ReusableRegisterPositions reset(int limit) {
    this.limit = limit;
    if (epoch == MAX_EPOCH) {
      Arrays.fill(entries, 0);
      epoch = 1;
    } else {
      epoch++;
    }
    epochBits = (long) epoch << EPOCH_SHIFT;
    return this;
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

  @Override
  public void set(int index, int value, LiveIntervals intervals) {
    ensureCapacity(index + 1);
    long entry = getEntry(index);
    long flags = entry & ((long) BLOCKED << FLAGS_SHIFT);
    if (intervals.isConstantNumberInterval()) {
      flags |= (long) HOLDS_CONSTANT << FLAGS_SHIFT;
    }
    if (intervals.usedInMonitorOperation()) {
      flags |= (long) HOLDS_MONITOR << FLAGS_SHIFT;
    }
    if (intervals.isNewStringInstanceDisallowingSpilling()) {
      flags |= (long) HOLDS_NEW_STRING_INSTANCE_DISALLOWING_SPILLING << FLAGS_SHIFT;
    }
    entries[index] = epochBits | flags | (value & POSITION_MASK);
  }

  @Override
  public int get(int index) {
    assert !isBlocked(index);
    if (index < entries.length) {
      return (int) getEntry(index);
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
    ensureCapacity(index + 1);
    entries[index] = getEntry(index) | ((long) BLOCKED << FLAGS_SHIFT);
  }

  @Override
  public boolean isBlocked(int index) {
    return (getFlags(index) & BLOCKED) != 0;
  }

  private int getFlags(int index) {
    return (int) ((getEntry(index) & FLAGS_MASK) >>> FLAGS_SHIFT);
  }

  private long getEntry(int index) {
    if (index < entries.length) {
      long entry = entries[index];
      if ((entry & EPOCH_MASK) == epochBits) {
        return entry;
      }
    }
    return epochBits | DEFAULT_POSITION;
  }

  private void ensureCapacity(int minSize) {
    if (minSize <= entries.length) {
      return;
    }
    int size = entries.length;
    while (size < minSize) {
      size *= 2;
    }
    entries = Arrays.copyOf(entries, size);
  }
}
