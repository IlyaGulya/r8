// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import com.android.tools.r8.errors.Unreachable;
import java.util.Arrays;
import java.util.BitSet;

public class RegisterPositionsImpl extends RegisterPositions {

  private final int limit;
  private final int[] backing;
  private final BitSet registerHoldsConstant;
  private final BitSet registerHoldsMonitor;
  private final BitSet registerHoldsNewStringInstanceDisallowingSpilling;
  private final BitSet blockedRegisters;

  public RegisterPositionsImpl(int limit) {
    this.limit = limit;
    backing = new int[limit];
    Arrays.fill(backing, Integer.MAX_VALUE);
    registerHoldsConstant = new BitSet(limit);
    registerHoldsMonitor = new BitSet(limit);
    registerHoldsNewStringInstanceDisallowingSpilling = new BitSet(limit);
    blockedRegisters = new BitSet(limit);
  }

  @Override
  public boolean hasType(int index, RegisterType type) {
    assert !isBlocked(index);
    switch (type) {
      case MONITOR:
        return holdsMonitor(index);
      case CONST_NUMBER:
        return holdsConstant(index);
      case OTHER:
        return !holdsMonitor(index)
            && !holdsConstant(index)
            && !holdsNewStringInstanceDisallowingSpilling(index);
      case ANY:
        return true;
      default:
        throw new Unreachable("Unexpected register position type: " + type);
    }
  }

  private boolean holdsConstant(int index) {
    return registerHoldsConstant.get(index);
  }

  private boolean holdsMonitor(int index) {
    return registerHoldsMonitor.get(index);
  }

  private boolean holdsNewStringInstanceDisallowingSpilling(int index) {
    return registerHoldsNewStringInstanceDisallowingSpilling.get(index);
  }

  private void set(int index, int value) {
    backing[index] = value;
  }

  @Override
  public void set(int index, int value, LiveIntervals intervals) {
    set(index, value);
    registerHoldsConstant.set(index, intervals.isConstantNumberInterval());
    registerHoldsMonitor.set(index, intervals.usedInMonitorOperation());
    registerHoldsNewStringInstanceDisallowingSpilling.set(
        index, intervals.isNewStringInstanceDisallowingSpilling());
  }

  @Override
  public int get(int index) {
    assert !isBlocked(index);
    if (index < limit) {
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
}
