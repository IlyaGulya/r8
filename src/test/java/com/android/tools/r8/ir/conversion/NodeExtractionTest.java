// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.conversion;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.ir.conversion.callgraph.CallGraph;
import com.android.tools.r8.ir.conversion.callgraph.CycleEliminator;
import com.android.tools.r8.ir.conversion.callgraph.Node;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

public class NodeExtractionTest extends CallGraphTestBase {

  private static final int LARGE_DEGREE = 70;

  // Note that building a test graph is intentionally repeated to avoid race conditions and/or
  // non-deterministic test results due to cycle elimination.

  @Test
  public void testExtractLeaves_withoutCycle() {
    Node n1, n2, n3, n4, n5, n6;
    Set<Node> nodes;

    n1 = createNode("n1");
    n2 = createNode("n2");
    n3 = createNode("n3");
    n4 = createNode("n4");
    n5 = createNode("n5");
    n6 = createNode("n6");

    n2.addCallerConcurrently(n1);
    n3.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n5);
    n6.addCallerConcurrently(n5);

    nodes = new TreeSet<>();
    nodes.add(n1);
    nodes.add(n2);
    nodes.add(n3);
    nodes.add(n4);
    nodes.add(n5);
    nodes.add(n6);

    CallGraph cg = CallGraph.createForTesting(nodes);
    Set<DexEncodedMethod> wave = cg.extractLeaves().toDefinitionSet();
    assertEquals(3, wave.size());
    assertThat(wave, hasItem(n3.getMethod()));
    assertThat(wave, hasItem(n4.getMethod()));
    assertThat(wave, hasItem(n6.getMethod()));

    wave = cg.extractLeaves().toDefinitionSet();
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n2.getMethod()));
    assertThat(wave, hasItem(n5.getMethod()));

    wave = cg.extractLeaves().toDefinitionSet();
    assertEquals(1, wave.size());
    assertThat(wave, hasItem(n1.getMethod()));
    assertTrue(cg.isEmpty());
  }

  @Test
  public void testExtractLeaves_withCycle() {
    Node n1, n2, n3, n4, n5, n6;
    Set<Node> nodes;

    n1 = createNode("n1");
    n2 = createNode("n2");
    n3 = createNode("n3");
    n4 = createNode("n4");
    n5 = createNode("n5");
    n6 = createNode("n6");

    n2.addCallerConcurrently(n1);
    n3.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n5);
    n6.addCallerConcurrently(n5);

    nodes = new TreeSet<>();
    nodes.add(n1);
    nodes.add(n2);
    nodes.add(n3);
    nodes.add(n4);
    nodes.add(n5);
    nodes.add(n6);

    n1.addCallerConcurrently(n3);
    n3.getMethod().getMutableOptimizationInfo().markForceInline();
    CycleEliminator cycleEliminator = new CycleEliminator();
    assertEquals(1, cycleEliminator.breakCycles(nodes).numberOfRemovedCallEdges());

    CallGraph cg = CallGraph.createForTesting(nodes);
    Set<DexEncodedMethod> wave = cg.extractLeaves().toDefinitionSet();
    assertEquals(3, wave.size());
    assertThat(wave, hasItem(n3.getMethod()));
    assertThat(wave, hasItem(n4.getMethod()));
    assertThat(wave, hasItem(n6.getMethod()));
    wave.clear();

    wave = cg.extractLeaves().toDefinitionSet();
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n2.getMethod()));
    assertThat(wave, hasItem(n5.getMethod()));
    wave.clear();

    wave = cg.extractLeaves().toDefinitionSet();
    assertEquals(1, wave.size());
    assertThat(wave, hasItem(n1.getMethod()));
    assertTrue(cg.isEmpty());
  }

  @Test
  public void testExtractRoots_withoutCycle() {
    Node n1, n2, n3, n4, n5, n6;
    Set<Node> nodes;

    n1 = createNode("n1");
    n2 = createNode("n2");
    n3 = createNode("n3");
    n4 = createNode("n4");
    n5 = createNode("n5");
    n6 = createNode("n6");

    n2.addCallerConcurrently(n1);
    n3.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n5);
    n6.addCallerConcurrently(n5);

    nodes = new TreeSet<>();
    nodes.add(n1);
    nodes.add(n2);
    nodes.add(n3);
    nodes.add(n4);
    nodes.add(n5);
    nodes.add(n6);

    CallGraph callGraph = CallGraph.createForTesting(nodes);
    Set<DexEncodedMethod> wave = callGraph.extractRoots().toDefinitionSet();
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n1.getMethod()));
    assertThat(wave, hasItem(n5.getMethod()));

    wave = callGraph.extractRoots().toDefinitionSet();
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n2.getMethod()));
    assertThat(wave, hasItem(n6.getMethod()));

    wave = callGraph.extractRoots().toDefinitionSet();
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n3.getMethod()));
    assertThat(wave, hasItem(n4.getMethod()));
    assertTrue(callGraph.isEmpty());
  }

  @Test
  public void testExtractRoots_withCycle() {
    Node n1, n2, n3, n4, n5, n6;
    Set<Node> nodes;

    n1 = createNode("n1");
    n2 = createNode("n2");
    n3 = createNode("n3");
    n4 = createNode("n4");
    n5 = createNode("n5");
    n6 = createNode("n6");

    n2.addCallerConcurrently(n1);
    n3.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n5);
    n6.addCallerConcurrently(n5);

    nodes = new TreeSet<>();
    nodes.add(n1);
    nodes.add(n2);
    nodes.add(n3);
    nodes.add(n4);
    nodes.add(n5);
    nodes.add(n6);

    n1.addCallerConcurrently(n3);
    n3.getMethod().getMutableOptimizationInfo().markForceInline();
    CycleEliminator cycleEliminator = new CycleEliminator();
    assertEquals(1, cycleEliminator.breakCycles(nodes).numberOfRemovedCallEdges());

    CallGraph callGraph = CallGraph.createForTesting(nodes);
    Set<DexEncodedMethod> wave = callGraph.extractRoots().toDefinitionSet();
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n1.getMethod()));
    assertThat(wave, hasItem(n5.getMethod()));

    wave = callGraph.extractRoots().toDefinitionSet();
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n2.getMethod()));
    assertThat(wave, hasItem(n6.getMethod()));

    wave = callGraph.extractRoots().toDefinitionSet();
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n3.getMethod()));
    assertThat(wave, hasItem(n4.getMethod()));
    assertTrue(callGraph.isEmpty());
  }

  @Test
  public void testSwitchExtractionDirection() {
    Node root = createNode("root");
    Node middle = createNode("middle");
    Node leaf = createNode("leaf");
    middle.addCallerConcurrently(root);
    leaf.addCallerConcurrently(middle);

    Set<Node> nodes = new TreeSet<>();
    nodes.add(root);
    nodes.add(middle);
    nodes.add(leaf);

    CallGraph callGraph = CallGraph.createForTesting(nodes);
    Set<DexEncodedMethod> wave = callGraph.extractLeaves().toDefinitionSet();
    assertEquals(1, wave.size());
    assertThat(wave, hasItem(leaf.getMethod()));
    wave = callGraph.extractRoots().toDefinitionSet();
    assertEquals(1, wave.size());
    assertThat(wave, hasItem(root.getMethod()));
    wave = callGraph.extractLeaves().toDefinitionSet();
    assertEquals(1, wave.size());
    assertThat(wave, hasItem(middle.getMethod()));
    assertTrue(callGraph.isEmpty());
  }

  @Test
  public void testExtractLeaves_largeCalleeSet() {
    Node caller = createNode("caller");
    Node middleCallee = null;
    Set<Node> nodes = new TreeSet<>();
    nodes.add(caller);
    for (int index = 0; index < LARGE_DEGREE; index++) {
      Node callee = createNode("callee" + index);
      callee.addCallerConcurrently(caller);
      nodes.add(callee);
      if (index == LARGE_DEGREE / 2) {
        middleCallee = callee;
      }
    }

    CallGraph callGraph = CallGraph.createForTesting(nodes);
    middleCallee.removeCaller(caller);
    assertFalse(caller.hasCallee(middleCallee));
    assertEquals(LARGE_DEGREE, callGraph.extractLeaves().size());
    assertEquals(1, callGraph.extractLeaves().size());
    assertTrue(callGraph.isEmpty());
  }

  @Test
  public void testExtractRoots_largeCallerSet() {
    Node callee = createNode("callee");
    Node middleCaller = null;
    Set<Node> nodes = new TreeSet<>();
    nodes.add(callee);
    for (int index = 0; index < LARGE_DEGREE; index++) {
      Node caller = createNode("caller" + index);
      callee.addCallerConcurrently(caller);
      nodes.add(caller);
      if (index == LARGE_DEGREE / 2) {
        middleCaller = caller;
      }
    }

    CallGraph callGraph = CallGraph.createForTesting(nodes);
    callee.removeCaller(middleCaller);
    assertFalse(callee.hasCaller(middleCaller));
    assertEquals(LARGE_DEGREE, callGraph.extractRoots().size());
    assertEquals(1, callGraph.extractRoots().size());
    assertTrue(callGraph.isEmpty());
  }
}
