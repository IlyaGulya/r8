// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion.passes.result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CodeRewriterResultTest {

  @Test
  public void testCanonicalResults() {
    assertFalse(CodeRewriterResult.NO_CHANGE.hasChanged().isTrue());
    assertEquals(
        IRCodeInvalidation.NONE, CodeRewriterResult.NO_CHANGE.invalidatedInvariants());
    assertTrue(CodeRewriterResult.HAS_CHANGED.hasChanged().isTrue());
    assertEquals(
        IRCodeInvalidation.ALL, CodeRewriterResult.HAS_CHANGED.invalidatedInvariants());
    assertTrue(CodeRewriterResult.NONE.hasChanged().isUnknown());
    assertEquals(IRCodeInvalidation.ALL, CodeRewriterResult.NONE.invalidatedInvariants());
  }

  @Test
  public void testPreciseResult() {
    int invalidatedInvariants =
        IRCodeInvalidation.INSTRUCTIONS | IRCodeInvalidation.SSA_VALUES;
    CodeRewriterResult result =
        CodeRewriterResult.hasChanged(true, invalidatedInvariants);
    assertTrue(result.hasChanged().isTrue());
    assertSame(CodeRewriterResult.SSA_VALUE_CHANGE, result);
    assertEquals(invalidatedInvariants, result.invalidatedInvariants());
  }
}
