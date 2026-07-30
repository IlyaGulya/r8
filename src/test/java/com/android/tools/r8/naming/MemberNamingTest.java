// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.naming;

import static org.junit.Assert.assertEquals;

import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import org.junit.Test;

public class MemberNamingTest {

  @Test
  public void compareToDoesNotMaterializeSignature() {
    MethodSignature signature =
        new MethodSignature("method", "void", new String[0]) {
          @Override
          public String toString() {
            throw new AssertionError("The signature is already equal on all ordering keys");
          }
        };
    MemberNaming first = new MemberNaming(signature, signature);
    MemberNaming second = new MemberNaming(signature, signature);

    assertEquals(0, first.compareTo(second));
  }
}
