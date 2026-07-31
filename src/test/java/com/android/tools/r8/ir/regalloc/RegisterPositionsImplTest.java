// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.regalloc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.ir.regalloc.RegisterPositions.RegisterType;
import org.junit.Test;

public class RegisterPositionsImplTest {

  @Test
  public void testInlineAndOverflowFlags() {
    RegisterPositionsImpl positions = new RegisterPositionsImpl(17);

    // Register hints can query beyond the nominal limit. BitSet-backed storage treats these
    // reads as empty, so a packed representation must preserve that behavior.
    assertFalse(positions.isBlocked(0));
    assertFalse(positions.isBlocked(15));
    assertFalse(positions.isBlocked(16));
    assertFalse(positions.isBlocked(18));
    assertTrue(positions.hasType(0, RegisterType.OTHER));
    assertTrue(positions.hasType(15, RegisterType.OTHER));
    assertTrue(positions.hasType(16, RegisterType.OTHER));
    assertTrue(positions.hasType(18, RegisterType.OTHER));

    // Preserve BitSet's grow-on-write behavior as well.
    positions.setBlocked(0);
    positions.setBlocked(15);
    positions.setBlocked(16);
    positions.setBlocked(18);
    assertTrue(positions.isBlocked(0));
    assertTrue(positions.isBlocked(15));
    assertTrue(positions.isBlocked(16));
    assertTrue(positions.isBlocked(18));
  }
}
