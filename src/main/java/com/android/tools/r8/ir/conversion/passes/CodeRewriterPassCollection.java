// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion.passes;

import com.android.tools.r8.contexts.CompilationContext.MethodProcessingContext;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.ir.analysis.constant.SparseConditionalConstantPropagation;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.conversion.IRConverter;
import com.android.tools.r8.ir.conversion.MethodProcessor;
import com.android.tools.r8.ir.conversion.passes.result.CodeRewriterResult;
import com.android.tools.r8.ir.conversion.passes.result.IRCodeInvalidation;
import com.android.tools.r8.ir.optimize.ListIterationRewriter;
import com.android.tools.r8.ir.optimize.RedundantFieldLoadAndStoreElimination;
import com.android.tools.r8.ir.optimize.ServiceLoaderRewriter;
import com.android.tools.r8.ir.optimize.ShareInstanceGetInstructions;
import com.android.tools.r8.ir.optimize.enums.EnumValueOptimizer;
import com.android.tools.r8.ir.optimize.string.StringBuilderAppendOptimizer;
import com.android.tools.r8.utils.ArrayUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.timing.Timing;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CodeRewriterPassCollection {

  public static final class Result {

    private final boolean hasChanged;
    private final String previousMethodPrinting;
    private final int invalidatedInvariants;

    private Result(boolean hasChanged, String previousMethodPrinting, int invalidatedInvariants) {
      assert IRCodeInvalidation.isValid(invalidatedInvariants);
      this.hasChanged = hasChanged;
      this.previousMethodPrinting = previousMethodPrinting;
      this.invalidatedInvariants = invalidatedInvariants;
    }

    public boolean hasChanged() {
      return hasChanged;
    }

    public String getPreviousMethodPrinting() {
      return previousMethodPrinting;
    }

    public int getInvalidatedInvariants() {
      return invalidatedInvariants;
    }
  }

  private final List<CodeRewriterPass<?>> passes;

  public CodeRewriterPassCollection(CodeRewriterPass<?>... passes) {
    this(Arrays.asList(passes));
  }

  public CodeRewriterPassCollection(List<CodeRewriterPass<?>> passes) {
    this.passes = passes;
  }

  public static CodeRewriterPassCollection create(AppView<?> appView) {
    List<CodeRewriterPass<?>> passes = new ArrayList<>();
    if (!appView.hasClassHierarchy()) {
      passes.add(new KotlinInlineMarkerRewriter(appView.withoutClassHierarchy()));
    }
    passes.add(new TrivialCheckCastAndInstanceOfRemover(appView));
    passes.add(new EnumValueOptimizer(appView));
    passes.add(new KnownArrayLengthRewriter(appView));
    passes.add(new NaturalIntLoopRemover(appView));
    passes.add(new CommonSubexpressionElimination(appView));
    passes.add(new ArrayConstructionSimplifier(appView));
    passes.add(new MoveResultRewriter(appView));
    passes.add(new StringBuilderAppendOptimizer(appView));
    passes.add(new SplitIntSwitch(appView));
    passes.add(new SparseConditionalConstantPropagation(appView));
    passes.add(new ThrowCatchOptimizer(appView));
    passes.add(new BranchSimplifier(appView));
    passes.add(new SplitBranch(appView));
    passes.add(new RedundantConstNumberRemover(appView));
    if (appView.options().isRelease()) {
      passes.add(new RedundantFieldLoadAndStoreElimination(appView));
    }
    passes.add(new BinopRewriter(appView));
    passes.add(new ServiceLoaderRewriter(appView));
    if (appView.options().isRelease()) {
      passes.add(new SplitReturnRewriter(appView));
      passes.add(new ReturnBlockCanonicalizerRewriter(appView));
    }
    passes.add(new ShareInstanceGetInstructions(appView));
    passes.add(new DivisionOptimizer(appView));
    if (appView.options().enableStringConcatInstruction) {
      passes.add(new StringConcatOptimizer(appView));
    }
    if (appView.hasClassHierarchy() && appView.getAtomicFieldUpdaterInstrumentorInfo() != null) {
      passes.add(new AtomicFieldUpdaterOptimizer(appView.withClassHierarchy()));
    }
    return new CodeRewriterPassCollection(passes);
  }

  public static CodeRewriterPassCollection createFromNullable(CodeRewriterPass<?>... passes) {
    return new CodeRewriterPassCollection(
        ArrayUtils.filterNulls(passes, CodeRewriterPass.EMPTY_ARRAY));
  }

  public Result run(
      IRCode code,
      MethodProcessor methodProcessor,
      MethodProcessingContext methodProcessingContext,
      Timing timing,
      String previousMethodPrinting,
      InternalOptions options) {
    boolean changed = false;
    int invalidatedInvariants = IRCodeInvalidation.NONE;
    for (CodeRewriterPass<?> pass : passes) {
      // TODO(b/286345542): Run printMethod after each run.
      CodeRewriterResult result = pass.run(code, methodProcessor, methodProcessingContext, timing);
      changed |= result.hasChanged().isTrue();
      invalidatedInvariants |= result.invalidatedInvariants();
      previousMethodPrinting =
          IRConverter.printMethodIR(
              code, "IR after " + pass.getRewriterId(), previousMethodPrinting, options);
    }
    return new Result(changed, previousMethodPrinting, invalidatedInvariants);
  }

  public void enableListIterationRewriter(AppView<?> appView) {
    passes.add(new ListIterationRewriter(appView));
  }
}
