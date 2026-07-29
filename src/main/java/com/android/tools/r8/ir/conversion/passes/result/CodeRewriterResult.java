// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion.passes.result;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.conversion.passes.BranchSimplifier.ControlFlowSimplificationResult;
import com.android.tools.r8.utils.OptionalBool;

public interface CodeRewriterResult {

  CodeRewriterResult NO_CHANGE = new DefaultCodeRewriterResult(false, IRCodeInvalidation.NONE);
  CodeRewriterResult HAS_CHANGED = new DefaultCodeRewriterResult(true, IRCodeInvalidation.ALL);
  CodeRewriterResult INSTRUCTION_CHANGE =
      new DefaultCodeRewriterResult(true, IRCodeInvalidation.INSTRUCTIONS);
  CodeRewriterResult SSA_VALUE_CHANGE =
      new DefaultCodeRewriterResult(
          true, IRCodeInvalidation.INSTRUCTIONS | IRCodeInvalidation.SSA_VALUES);
  CodeRewriterResult CFG_CHANGE =
      new DefaultCodeRewriterResult(
          true,
          IRCodeInvalidation.INSTRUCTIONS
              | IRCodeInvalidation.SSA_VALUES
              | IRCodeInvalidation.CFG_TOPOLOGY);
  CodeRewriterResult CFG_CHANGE_WITH_REDUNDANT_BLOCKS =
      new DefaultCodeRewriterResult(
          true,
          IRCodeInvalidation.INSTRUCTIONS
              | IRCodeInvalidation.SSA_VALUES
              | IRCodeInvalidation.CFG_TOPOLOGY
              | IRCodeInvalidation.REDUNDANT_BLOCKS);
  CodeRewriterResult NONE = OptionalBool::unknown;

  static CodeRewriterResult hasChanged(boolean hasChanged) {
    return hasChanged ? HAS_CHANGED : NO_CHANGE;
  }

  static CodeRewriterResult hasChanged(boolean hasChanged, int invalidatedInvariants) {
    if (!hasChanged) {
      assert invalidatedInvariants == IRCodeInvalidation.NONE;
      return NO_CHANGE;
    }
    if (invalidatedInvariants == IRCodeInvalidation.ALL) {
      return HAS_CHANGED;
    }
    if (invalidatedInvariants == IRCodeInvalidation.INSTRUCTIONS) {
      return INSTRUCTION_CHANGE;
    }
    if (invalidatedInvariants
        == (IRCodeInvalidation.INSTRUCTIONS | IRCodeInvalidation.SSA_VALUES)) {
      return SSA_VALUE_CHANGE;
    }
    if (invalidatedInvariants
        == (IRCodeInvalidation.INSTRUCTIONS
            | IRCodeInvalidation.SSA_VALUES
            | IRCodeInvalidation.CFG_TOPOLOGY)) {
      return CFG_CHANGE;
    }
    if (invalidatedInvariants
        == (IRCodeInvalidation.INSTRUCTIONS
            | IRCodeInvalidation.SSA_VALUES
            | IRCodeInvalidation.CFG_TOPOLOGY
            | IRCodeInvalidation.REDUNDANT_BLOCKS)) {
      return CFG_CHANGE_WITH_REDUNDANT_BLOCKS;
    }
    return new DefaultCodeRewriterResult(true, invalidatedInvariants);
  }

  class DefaultCodeRewriterResult implements CodeRewriterResult {

    private final boolean hasChanged;
    private final int invalidatedInvariants;

    public DefaultCodeRewriterResult(boolean hasChanged, int invalidatedInvariants) {
      assert hasChanged || invalidatedInvariants == IRCodeInvalidation.NONE;
      assert IRCodeInvalidation.isValid(invalidatedInvariants);
      this.hasChanged = hasChanged;
      this.invalidatedInvariants = invalidatedInvariants;
    }

    @Override
    public OptionalBool hasChanged() {
      return OptionalBool.of(hasChanged);
    }

    @Override
    public int invalidatedInvariants() {
      return invalidatedInvariants;
    }
  }

  OptionalBool hasChanged();

  // Unknown and legacy results conservatively invalidate all tracked invariants.
  default int invalidatedInvariants() {
    return IRCodeInvalidation.ALL;
  }

  default ControlFlowSimplificationResult asControlFlowSimplificationResult() {
    throw new Unreachable("Not a control flow simplification result.");
  }
}
