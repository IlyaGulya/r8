// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.ir.regalloc.RegisterPositions.RegisterType;
import org.junit.Test;

public class ReusableRegisterPositionsTest {

  @Test
  public void testResetMakesPreviousEntriesImplicitlyEmpty() {
    ReusableRegisterPositions positions = new ReusableRegisterPositions().reset(17);

    assertEquals(Integer.MAX_VALUE, positions.get(0));
    assertEquals(Integer.MAX_VALUE, positions.get(16));
    assertFalse(positions.isBlocked(0));
    assertFalse(positions.isBlocked(16));
    assertTrue(positions.hasType(0, RegisterType.OTHER));

    positions.setBlocked(0);
    positions.setBlocked(16);
    assertTrue(positions.isBlocked(0));
    assertTrue(positions.isBlocked(16));

    positions.reset(9);
    assertEquals(9, positions.getLimit());
    assertEquals(Integer.MAX_VALUE, positions.get(0));
    assertFalse(positions.isBlocked(0));
    assertFalse(positions.isBlocked(16));
    assertTrue(positions.hasType(16, RegisterType.OTHER));
  }
}
