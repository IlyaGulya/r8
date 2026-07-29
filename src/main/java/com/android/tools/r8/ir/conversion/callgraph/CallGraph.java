// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion.callgraph;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.ir.conversion.MethodProcessorWithWave;
import com.android.tools.r8.ir.conversion.callgraph.CallSiteInformation.CallGraphBasedCallSiteInformation;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.collections.ProgramMethodSet;
import com.android.tools.r8.utils.collections.SortedProgramMethodSet;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Call graph representation.
 *
 * <p>Each node in the graph contain the methods called and the calling methods. For virtual and
 * interface calls all potential calls from subtypes are recorded.
 *
 * <p>Only methods in the program - not library methods - are
 * represented. @SuppressWarnings("UnusedVariable")
 *
 * <p>The directional edges are represented as sets of nodes in each node (called methods and
 * callees).
 *
 * <p>A call from method <code>a</code> to method <code>b</code> is only present once no matter how
 * many calls of <code>a</code> there are in <code>a</code>.
 *
 * <p>Recursive calls are not present.
 */
public class CallGraph extends CallGraphBase<Node> {

  private final Node[] orderedNodes;

  private boolean extractingLeaves;
  private boolean extractionInitialized;
  private boolean filterInactiveNodes;
  private int[] remainingEdgeCounts;
  private int[] readyNodes;
  private int readyNodesTail;
  private int waveStart;
  private int waveEnd;

  CallGraph(Map<DexMethod, Node> nodes, Node[] orderedNodes) {
    super(nodes);
    this.orderedNodes = orderedNodes;
  }

  public static CallGraphBuilder builder(AppView<AppInfoWithLiveness> appView) {
    return new CallGraphBuilder(appView);
  }

  public static CallGraph createForTesting(Collection<Node> nodes) {
    Node[] orderedNodes = Node.prepareForDeterministicTraversal(nodes);
    return new CallGraph(
        nodes.stream()
            .collect(
                Collectors.toMap(
                    node -> node.getProgramMethod().getReference(), Function.identity())),
        orderedNodes);
  }

  public CallSiteInformation createCallSiteInformation(
      AppView<AppInfoWithLiveness> appView, MethodProcessorWithWave methodProcessor) {
    return needsCallSiteInformation(appView)
        ? new CallGraphBasedCallSiteInformation(appView, this, methodProcessor)
        : CallSiteInformation.empty();
  }

  private boolean needsCallSiteInformation(AppView<AppInfoWithLiveness> appView) {
    InternalOptions options = appView.options();
    if (options.debug) {
      return false;
    }
    if (options.isOptimizing() && options.isShrinking()) {
      return true;
    }
    // When we are neither optimizing nor shrinking, we still allow inlining of javac synthetic
    // lambda methods into R8 generated accessor methods.
    return options.isGeneratingDex();
  }

  public ProgramMethodSet extractLeaves() {
    return extractNodes(true, ignore -> {});
  }

  public ProgramMethodSet extractLeaves(Consumer<Node> nodeRemovalConsumer) {
    return extractNodes(true, nodeRemovalConsumer);
  }

  public ProgramMethodSet extractRoots() {
    return extractNodes(false, ignore -> {});
  }

  private ProgramMethodSet extractNodes(boolean leaves, Consumer<Node> nodeRemovalConsumer) {
    if (!extractionInitialized || extractingLeaves != leaves) {
      initializeExtraction(leaves);
    }
    int waveSize = waveEnd - waveStart;
    assert waveSize > 0;
    ProgramMethodSet result =
        InternalOptions.DETERMINISTIC_DEBUGGING
            ? SortedProgramMethodSet.create()
            : ProgramMethodSet.create(waveSize);
    for (int index = waveStart; index < waveEnd; index++) {
      Node node = orderedNodes[readyNodes[index]];
      Node removed = nodes.remove(node.getProgramMethod().getReference());
      assert removed == node;
      result.add(node.getProgramMethod());
      nodeRemovalConsumer.accept(node);
    }
    int nextWaveStart = waveEnd;
    for (int index = waveStart; index < waveEnd; index++) {
      Node node = orderedNodes[readyNodes[index]];
      if (leaves) {
        for (Node caller : node.getCallers()) {
          decrementRemainingEdgeCount(caller);
        }
        for (Node reader : node.getReadersWithDeterministicOrder()) {
          decrementRemainingEdgeCount(reader);
        }
      } else {
        for (Node callee : node.getCalleesWithDeterministicOrder()) {
          decrementRemainingEdgeCount(callee);
        }
        for (Node writer : node.getWritersWithDeterministicOrder()) {
          decrementRemainingEdgeCount(writer);
        }
      }
    }
    waveStart = nextWaveStart;
    waveEnd = readyNodesTail;
    assert !result.isEmpty();
    return result;
  }

  private void initializeExtraction(boolean leaves) {
    extractingLeaves = leaves;
    extractionInitialized = true;
    filterInactiveNodes = nodes.size() != orderedNodes.length;
    remainingEdgeCounts = new int[orderedNodes.length];
    readyNodes = new int[nodes.size()];
    readyNodesTail = 0;
    waveStart = 0;
    for (Node node : orderedNodes) {
      if (filterInactiveNodes && !nodes.containsKey(node.getProgramMethod().getReference())) {
        continue;
      }
      int remainingEdgeCount = countRemainingEdges(node, leaves);
      int nodeIndex = node.getCallGraphOrder();
      remainingEdgeCounts[nodeIndex] = remainingEdgeCount;
      if (remainingEdgeCount == 0) {
        readyNodes[readyNodesTail++] = nodeIndex;
      }
    }
    waveEnd = readyNodesTail;
    assert nodes.isEmpty() || waveEnd > 0;
  }

  private int countRemainingEdges(Node node, boolean leaves) {
    if (!filterInactiveNodes) {
      return leaves
          ? node.getCalleesWithDeterministicOrder().size()
              + node.getWritersWithDeterministicOrder().size()
          : node.getCallers().size() + node.getReadersWithDeterministicOrder().size();
    }
    int count = 0;
    if (leaves) {
      for (Node callee : node.getCalleesWithDeterministicOrder()) {
        if (nodes.containsKey(callee.getProgramMethod().getReference())) {
          count++;
        }
      }
      for (Node writer : node.getWritersWithDeterministicOrder()) {
        if (nodes.containsKey(writer.getProgramMethod().getReference())) {
          count++;
        }
      }
    } else {
      for (Node caller : node.getCallers()) {
        if (nodes.containsKey(caller.getProgramMethod().getReference())) {
          count++;
        }
      }
      for (Node reader : node.getReadersWithDeterministicOrder()) {
        if (nodes.containsKey(reader.getProgramMethod().getReference())) {
          count++;
        }
      }
    }
    return count;
  }

  private void decrementRemainingEdgeCount(Node node) {
    if (filterInactiveNodes && !nodes.containsKey(node.getProgramMethod().getReference())) {
      return;
    }
    int nodeIndex = node.getCallGraphOrder();
    int remainingEdgeCount = --remainingEdgeCounts[nodeIndex];
    assert remainingEdgeCount >= 0;
    if (remainingEdgeCount == 0) {
      assert readyNodesTail < readyNodes.length;
      readyNodes[readyNodesTail++] = nodeIndex;
    }
  }
}
