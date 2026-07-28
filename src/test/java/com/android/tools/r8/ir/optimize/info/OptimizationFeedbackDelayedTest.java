// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.info;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.FieldAccessFlags;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.ir.optimize.Inliner.ConstraintWithTarget;
import org.junit.Test;

public class OptimizationFeedbackDelayedTest {

  @Test
  public void testApplyCannotBeKeptUpdates() {
    DexEncodedField field = createField();
    DexEncodedMethod method = createMethod("live");
    OptimizationFeedbackDelayed feedback = new OptimizationFeedbackDelayed();

    assertFalse(field.getOptimizationInfo().cannotBeKept());
    assertFalse(method.getOptimizationInfo().cannotBeKept());
    feedback.markFieldCannotBeKept(field);
    feedback.markMethodCannotBeKept(method);
    feedback.updateVisibleOptimizationInfo();

    assertTrue(field.getOptimizationInfo().cannotBeKept());
    assertTrue(method.getOptimizationInfo().cannotBeKept());
    assertTrue(feedback.noUpdatesLeft());
  }

  @Test
  public void testApplyNonObsoleteMethodUpdates() {
    DexEncodedMethod method = createMethod("live");
    OptimizationFeedbackDelayed feedback = new OptimizationFeedbackDelayed();

    assertTrue(method.getOptimizationInfo().mayHaveSideEffects());
    assertFalse(method.isProcessed());
    feedback.methodMayNotHaveSideEffects(method);
    feedback.markProcessed(method, ConstraintWithTarget.NEVER);
    feedback.updateVisibleOptimizationInfo();

    assertFalse(method.getOptimizationInfo().mayHaveSideEffects());
    assertTrue(method.isProcessed());
    assertTrue(feedback.noUpdatesLeft());
  }

  @Test
  public void testSkipObsoleteMethodUpdates() {
    DexEncodedMethod method = createMethod("obsolete");
    OptimizationFeedbackDelayed feedback = new OptimizationFeedbackDelayed();

    feedback.methodMayNotHaveSideEffects(method);
    feedback.markProcessed(method, ConstraintWithTarget.NEVER);
    method.setObsolete();
    feedback.updateVisibleOptimizationInfo();

    assertTrue(feedback.noUpdatesLeft());
  }

  private static DexEncodedField createField() {
    DexItemFactory factory = new DexItemFactory();
    return DexEncodedField.builder()
        .setField(factory.createField(factory.objectType, factory.intType, "field"))
        .setAccessFlags(FieldAccessFlags.fromSharedAccessFlags(0))
        .disableAndroidApiLevelCheck()
        .build();
  }

  private static DexEncodedMethod createMethod(String name) {
    DexItemFactory factory = new DexItemFactory();
    return DexEncodedMethod.builder()
        .setMethod(
            factory.createMethod(
                factory.objectType, factory.createProto(factory.voidType), name))
        .setAccessFlags(MethodAccessFlags.fromDexAccessFlags(0))
        .disableAndroidApiLevelCheck()
        .build();
  }
}
