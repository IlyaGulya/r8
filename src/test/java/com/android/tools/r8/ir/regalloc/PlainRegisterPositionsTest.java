// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.android.tools.r8.errors.Unreachable;
import org.junit.Test;

public class PlainRegisterPositionsTest {

  @Test
  public void testPositionOnlyTable() {
    PlainRegisterPositions positions = new PlainRegisterPositions(256);
    assertEquals(Integer.MAX_VALUE, positions.get(0));
    assertEquals(Integer.MAX_VALUE, positions.get(255));

    positions.set(15, 42, null);
    positions.set(31, 84, null);
    positions.set(255, 126, null);

    assertEquals(42, positions.get(15));
    assertEquals(84, positions.get(31));
    assertEquals(126, positions.get(255));
    assertEquals(Integer.MAX_VALUE, positions.get(254));
  }

  @Test
  public void testUnsupportedStateOperationsFail() {
    PlainRegisterPositions positions = new PlainRegisterPositions(16);
    assertThrows(Unreachable.class, () -> positions.hasType(0, RegisterPositions.RegisterType.ANY));
    assertThrows(Unreachable.class, () -> positions.setBlocked(0));
    assertThrows(Unreachable.class, () -> positions.isBlocked(0));
  }
}
