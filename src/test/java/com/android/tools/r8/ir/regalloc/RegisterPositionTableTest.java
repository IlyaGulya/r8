// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RegisterPositionTableTest {

  @Test
  public void testPositionOnlyTable() {
    RegisterPositionTable positions = new RegisterPositionTable(256);
    assertEquals(Integer.MAX_VALUE, positions.get(0));
    assertEquals(Integer.MAX_VALUE, positions.get(255));

    positions.set(15, 42);
    positions.set(31, 84);
    positions.set(255, 126);

    assertEquals(42, positions.get(15));
    assertEquals(84, positions.get(31));
    assertEquals(126, positions.get(255));
    assertEquals(Integer.MAX_VALUE, positions.get(254));
    assertEquals(42, positions.get(15, false));
    assertEquals(42, positions.get(15, true));
    assertEquals(84, positions.get(30, true));
  }
}
