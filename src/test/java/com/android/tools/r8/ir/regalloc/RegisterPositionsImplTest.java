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
  public void testFlagsOutsideInitialPlane() {
    RegisterPositionsImpl positions = new RegisterPositionsImpl(17);

    assertFalse(positions.isBlocked(18));
    assertTrue(positions.hasType(18, RegisterType.OTHER));

    positions.setBlocked(18);
    assertTrue(positions.isBlocked(18));
  }
}
