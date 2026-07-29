// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion.callgraph;

import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;

public class Node extends NodeBase<Node> implements Comparable<Node>, CycleEliminatorNode<Node> {

  public static Node[] EMPTY_ARRAY = {};
  private static final Comparator<Node> METHOD_SIGNATURE_COMPARATOR =
      (first, second) ->
          first
              .getProgramMethod()
              .getReference()
              .compareSignatureTo(second.getProgramMethod().getReference());

  private int numberOfCallSites = 0;
  private int callGraphOrder = -1;

  // Outgoing calls from this method.
  private final CallGraphNodeSet callees = new CallGraphNodeSet();

  // Incoming calls to this method.
  private final CallGraphNodeSet callers = new CallGraphNodeSet();

  // Incoming field read edges to this method (i.e., the set of methods that read a field written
  // by the current method).
  private final CallGraphNodeSet readers = new CallGraphNodeSet();

  // Outgoing field read edges from this method (i.e., the set of methods that write a field read
  // by the current method).
  private final CallGraphNodeSet writers = new CallGraphNodeSet();

  public Node(ProgramMethod method) {
    super(method);
  }

  public void addCallerConcurrently(Node caller) {
    addCallerConcurrently(caller, false);
  }

  @Override
  public void addCallerConcurrently(Node caller, boolean likelySpuriousCallEdge) {
    if (caller != this && !likelySpuriousCallEdge) {
      boolean changedCallers;
      synchronized (callers) {
        changedCallers = callers.add(caller);
        numberOfCallSites++;
      }
      if (changedCallers) {
        synchronized (caller.callees) {
          caller.callees.add(this);
        }
        // Avoid redundant field read edges (call edges are considered stronger).
        removeReaderConcurrently(caller);
      }
    } else {
      synchronized (callers) {
        numberOfCallSites++;
      }
    }
  }

  @Override
  public void addLikelySpuriousCallSites(int count) {
    synchronized (callers) {
      numberOfCallSites += count;
    }
  }

  @Override
  public void addReaderConcurrently(Node reader) {
    if (reader != this) {
      synchronized (callers) {
        if (callers.contains(reader)) {
          // Avoid redundant field read edges (call edges are considered stronger).
          return;
        }
        boolean readersChanged;
        synchronized (readers) {
          readersChanged = readers.add(reader);
        }
        if (readersChanged) {
          synchronized (reader.writers) {
            reader.writers.add(this);
          }
        }
      }
    }
  }

  private void removeReaderConcurrently(Node reader) {
    synchronized (readers) {
      readers.remove(reader);
    }
    synchronized (reader.writers) {
      reader.writers.remove(this);
    }
  }

  @Override
  public void removeCaller(Node caller) {
    boolean callersChanged = callers.remove(caller);
    assert callersChanged;
    boolean calleesChanged = caller.callees.remove(this);
    assert calleesChanged;
    assert !hasReader(caller);
  }

  @Override
  public void removeReader(Node reader) {
    boolean readersChanged = readers.remove(reader);
    assert readersChanged;
    boolean writersChanged = reader.writers.remove(this);
    assert writersChanged;
    assert !hasCaller(reader);
  }

  public void cleanCalleesAndWritersForRemoval() {
    assert callers.isEmpty();
    assert readers.isEmpty();
    for (Node callee : callees) {
      boolean changed = callee.callers.remove(this);
      assert changed;
    }
    for (Node writer : writers) {
      boolean changed = writer.readers.remove(this);
      assert changed;
    }
  }

  public void cleanCallersAndReadersForRemoval() {
    assert callees.isEmpty();
    assert writers.isEmpty();
    for (Node caller : callers) {
      boolean changed = caller.callees.remove(this);
      assert changed;
    }
    for (Node reader : readers) {
      boolean changed = reader.writers.remove(this);
      assert changed;
    }
  }

  public Set<Node> getCallers() {
    return callers;
  }

  public Set<Node> getCallersWithDeterministicOrder() {
    return callers.getWithDeterministicOrder();
  }

  @Override
  public Set<Node> getCalleesWithDeterministicOrder() {
    return callees.getWithDeterministicOrder();
  }

  public Set<Node> getReadersWithDeterministicOrder() {
    return readers.getWithDeterministicOrder();
  }

  @Override
  public Set<Node> getWritersWithDeterministicOrder() {
    return writers.getWithDeterministicOrder();
  }

  int getCallGraphOrder() {
    return callGraphOrder;
  }

  void setCallGraphOrder(int callGraphOrder) {
    assert this.callGraphOrder < 0;
    this.callGraphOrder = callGraphOrder;
  }

  void freezeCallGraphEdges() {
    assert callGraphOrder >= 0;
    callees.freeze();
    callers.freeze();
    readers.freeze();
    writers.freeze();
  }

  static Node[] prepareForDeterministicTraversal(Collection<Node> nodes) {
    Node[] orderedNodes = nodes.toArray(EMPTY_ARRAY);
    sortByMethodReference(orderedNodes);
    for (int index = 0; index < orderedNodes.length; index++) {
      orderedNodes[index].setCallGraphOrder(index);
    }
    for (Node node : orderedNodes) {
      node.freezeCallGraphEdges();
    }
    return orderedNodes;
  }

  private static void sortByMethodReference(Node[] nodes) {
    if (nodes.length < 2) {
      return;
    }
    DexType firstHolder = nodes[0].getProgramMethod().getHolderType();
    int factoryIdentity = firstHolder.getFactoryIdentity();
    int maxFactoryId = firstHolder.getFactoryId();
    if (factoryIdentity < 0 || maxFactoryId < 0) {
      Arrays.sort(nodes);
      return;
    }
    for (int index = 1; index < nodes.length; index++) {
      DexType holder = nodes[index].getProgramMethod().getHolderType();
      if (holder.getFactoryIdentity() != factoryIdentity || holder.getFactoryId() < 0) {
        Arrays.sort(nodes);
        return;
      }
      maxFactoryId = Math.max(maxFactoryId, holder.getFactoryId());
    }
    if (maxFactoryId >= nodes.length) {
      Arrays.sort(nodes);
      return;
    }

    int[] counts = new int[maxFactoryId + 1];
    int[] usedFactoryIds = new int[Math.min(nodes.length, counts.length)];
    int holderCount = 0;
    for (Node node : nodes) {
      int factoryId = node.getProgramMethod().getHolderType().getFactoryId();
      if (counts[factoryId]++ == 0) {
        usedFactoryIds[holderCount++] = factoryId;
      }
    }
    int[] nextOffsets = new int[counts.length];
    int nextOffset = 0;
    for (int index = 0; index < holderCount; index++) {
      int factoryId = usedFactoryIds[index];
      nextOffsets[factoryId] = nextOffset;
      nextOffset += counts[factoryId];
    }

    Node[] groupedNodes = new Node[nodes.length];
    for (Node node : nodes) {
      int factoryId = node.getProgramMethod().getHolderType().getFactoryId();
      groupedNodes[nextOffsets[factoryId]++] = node;
    }
    DexType[] holders = new DexType[holderCount];
    for (int index = 0; index < holderCount; index++) {
      int factoryId = usedFactoryIds[index];
      int count = counts[factoryId];
      holders[index] =
          groupedNodes[nextOffsets[factoryId] - count].getProgramMethod().getHolderType();
    }
    Arrays.sort(holders);

    nextOffset = 0;
    for (DexType holder : holders) {
      int factoryId = holder.getFactoryId();
      int count = counts[factoryId];
      int endOffset = nextOffsets[factoryId];
      int startOffset = endOffset - count;
      if (count > 1) {
        Arrays.sort(groupedNodes, startOffset, endOffset, METHOD_SIGNATURE_COMPARATOR);
      }
      for (int index = startOffset; index < endOffset; index++) {
        nodes[nextOffset++] = groupedNodes[index];
      }
    }
    assert verifySorted(nodes);
  }

  private static boolean verifySorted(Node[] nodes) {
    for (int index = 1; index < nodes.length; index++) {
      assert nodes[index - 1].compareTo(nodes[index]) <= 0;
    }
    return true;
  }

  public int getNumberOfCallSites() {
    return numberOfCallSites;
  }

  @Override
  public boolean hasCallee(Node method) {
    return callees.contains(method);
  }

  @Override
  public boolean hasCaller(Node method) {
    return callers.contains(method);
  }

  @Override
  public boolean hasReader(Node method) {
    return readers.contains(method);
  }

  @Override
  public boolean hasWriter(Node method) {
    return writers.contains(method);
  }

  public boolean isRoot() {
    return callers.isEmpty() && readers.isEmpty();
  }

  public boolean isLeaf() {
    return callees.isEmpty() && writers.isEmpty();
  }

  @Override
  public int compareTo(Node other) {
    return getProgramMethod().getReference().compareTo(other.getProgramMethod().getReference());
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("MethodNode for: ");
    builder.append(getProgramMethod().toSourceString());
    builder.append(" (");
    builder.append(callees.size());
    builder.append(" callees, ");
    builder.append(callers.size());
    builder.append(" callers");
    builder.append(", invoke count ").append(numberOfCallSites);
    builder.append(").");
    builder.append(System.lineSeparator());
    if (callees.size() > 0) {
      builder.append("Callees:");
      builder.append(System.lineSeparator());
      for (Node call : callees) {
        builder.append("  ");
        builder.append(call.getProgramMethod().toSourceString());
        builder.append(System.lineSeparator());
      }
    }
    if (callers.size() > 0) {
      builder.append("Callers:");
      builder.append(System.lineSeparator());
      for (Node caller : callers) {
        builder.append("  ");
        builder.append(caller.getProgramMethod().toSourceString());
        builder.append(System.lineSeparator());
      }
    }
    return builder.toString();
  }
}
