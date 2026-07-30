// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.code.Value;
import java.util.Collections;
import org.junit.Test;

public class AffectedValuesTest {

  private static Value value(int number) {
    return Value.createNoDebugLocal(number, TypeElement.getBottom());
  }

  @Test
  public void testLazySetContract() {
    AffectedValues affectedValues = new AffectedValues();
    Value first = value(0);
    Value second = value(1);

    assertTrue(affectedValues.isEmpty());
    assertFalse(affectedValues.hasNext());
    assertEquals(0, affectedValues.size());
    assertFalse(affectedValues.iterator().hasNext());
    assertTrue(affectedValues.containsAll(Collections.emptyList()));
    assertArrayEquals(new Object[0], affectedValues.toArray());

    Value[] destination = {first};
    assertSame(destination, affectedValues.toArray(destination));
    assertNull(destination[0]);

    assertTrue(affectedValues.add(first));
    assertFalse(affectedValues.add(first));
    assertTrue(affectedValues.add(second));
    assertTrue(affectedValues.contains(first));
    assertTrue(affectedValues.contains(second));
    assertEquals(2, affectedValues.size());

    affectedValues.clear();
    assertTrue(affectedValues.isEmpty());
    assertFalse(affectedValues.contains(first));
    assertFalse(affectedValues.remove(first));

    assertTrue(affectedValues.add(first));
    assertEquals(1, affectedValues.size());
    assertTrue(affectedValues.remove(first));
    assertTrue(affectedValues.isEmpty());
  }
}
