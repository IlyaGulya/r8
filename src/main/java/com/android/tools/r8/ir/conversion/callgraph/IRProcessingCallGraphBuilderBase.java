// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.conversion.callgraph;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.timing.Timing;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

abstract class IRProcessingCallGraphBuilderBase extends CallGraphBuilderBase<Node> {

  IRProcessingCallGraphBuilderBase(AppView<AppInfoWithLiveness> appView) {
    super(appView);
  }

  public CallGraph build(ExecutorService executorService, Timing timing) throws ExecutionException {
    timing.begin("Build IR processing order constraints");
    timing.begin("Build call graph");
    populateGraph(executorService);
    flushLikelySpuriousCallSites();
    assert verifyNoRedundantFieldReadEdges();
    timing.end();
    assert verifyAllMethodsWithCodeExists();

    appView.withGeneratedMessageLiteBuilderShrinker(
        shrinker -> shrinker.preprocessCallGraphBeforeCycleElimination(nodes));

    Node[] orderedNodes = Node.prepareForDeterministicTraversal(nodes.values());

    timing.begin("Cycle elimination");
    Collection<Node> nodesWithDeterministicOrder = Arrays.asList(orderedNodes);
    CycleEliminator cycleEliminator = new CycleEliminator();
    cycleEliminator.breakCycles(nodesWithDeterministicOrder);
    timing.end();
    timing.end();
    assert cycleEliminator.breakCycles(nodesWithDeterministicOrder).numberOfRemovedCallEdges()
        == 0; // The cycles should be gone.

    // The graph is populated concurrently, but consumed sequentially in processing waves. Avoid
    // retaining the concurrent iteration overhead in CallGraph.extractNodes().
    return new CallGraph(new HashMap<>(nodes));
  }

  @Override
  protected Node createNode(ProgramMethod method) {
    return new Node(method);
  }

  abstract void populateGraph(ExecutorService executorService) throws ExecutionException;

  /** Verify that there are no field read edges in the graph if there is also a call graph edge. */
  private boolean verifyNoRedundantFieldReadEdges() {
    for (Node writer : nodes.values()) {
      for (Node reader : writer.getReadersWithDeterministicOrder()) {
        assert !writer.hasCaller(reader);
      }
    }
    return true;
  }

  abstract boolean verifyAllMethodsWithCodeExists();
}
