// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.ir.optimize.DeadCodeRemover.DeadInstructionResult;
import com.google.common.collect.Iterables;
import java.util.Arrays;

public class ValueIsDeadAnalysis {

  private static final byte UNKNOWN = 0;
  private static final byte DEAD = 1;
  private static final byte NOT_DEAD = 2;
  private static final int INITIAL_BUFFER_CAPACITY = 8;

  private final AppView<?> appView;
  private final IRCode code;

  private byte[] analysisCache;
  private int[] seenEpochs;
  private int[] requiredEpochs;
  private int[] firstDependentEdgeEpochs;
  private int[] firstDependentEdges;
  private int[] remainingDependencies;
  private Value[] valuesByNumber;

  private int[] workQueue = new int[INITIAL_BUFFER_CAPACITY];
  private int workQueueHead;
  private int workQueueTail;

  private int[] requiredValues = new int[INITIAL_BUFFER_CAPACITY];
  private int requiredValuesSize;

  private int[] propagationQueue = new int[INITIAL_BUFFER_CAPACITY];

  private int[] dependentEdgeTargets = new int[INITIAL_BUFFER_CAPACITY];
  private int[] nextDependentEdges = new int[INITIAL_BUFFER_CAPACITY];
  private int dependentEdgesSize;

  private int currentEpoch;
  private int currentRequiredEpoch;
  private boolean foundCycle;

  public ValueIsDeadAnalysis(AppView<?> appView, IRCode code) {
    this.appView = appView;
    this.code = code;
    int valueCapacity = Math.max(code.valueNumberGenerator.peek(), 1);
    analysisCache = new byte[valueCapacity];
    seenEpochs = new int[valueCapacity];
    requiredEpochs = new int[valueCapacity];
    firstDependentEdgeEpochs = new int[valueCapacity];
    firstDependentEdges = new int[valueCapacity];
    remainingDependencies = new int[valueCapacity];
    valuesByNumber = new Value[valueCapacity];
  }

  public boolean isDead(Value value) {
    // Totally unused values are trivially dead.
    if (value.isUnused()) {
      return true;
    }
    // Create an is-dead dependence graph. If the deadness of value v depends on value u being dead,
    // a directed edge [v -> u] is added to the graph.
    //
    // Only reverse edges and the number of unresolved dependencies are needed. If `u` is not dead,
    // reverse edges find all dependent values. If `u` is dead, decrementing each dependent's
    // unresolved count identifies the new leaves.
    startQuery(value);
    boolean isDead = findNotDeadWitness();
    if (isDead) {
      if (foundCycle) {
        for (int index = 0; index < workQueueTail; index++) {
          recordValueIsDead(workQueue[index]);
        }
      } else {
        assert verifySeenValuesAreDead();
      }
    }
    clearQueryValueReferences();
    return isDead;
  }

  public boolean hasDeadPhi(BasicBlock block) {
    return Iterables.any(block.getPhis(), this::isDead);
  }

  private boolean findNotDeadWitness() {
    while (workQueueHead < workQueueTail) {
      int valueNumber = workQueue[workQueueHead++];
      Value value = valuesByNumber[valueNumber];

      // The first time we visit a value we have not yet added any outgoing edges to the dependence
      // graph.
      assert remainingDependencies[valueNumber] == 0;

      // Lookup if we have already analyzed the deadness of this value.
      byte cacheResult = analysisCache[valueNumber];
      if (cacheResult != UNKNOWN) {
        // If it is dead, then continue the search for a non-dead dependent. Otherwise this value is
        // a witness that the analysis failed.
        if (cacheResult == DEAD) {
          continue;
        } else {
          assert cacheResult == NOT_DEAD;
          recordDependentsAreNotDead(valueNumber);
          return false;
        }
      }

      // If the value has debug users we cannot eliminate it since it represents a value in a local
      // variable that should be visible in the debugger.
      if (value.hasDebugUsers()) {
        recordValueAndDependentsAreNotDead(valueNumber);
        return false;
      }

      startRequiredValues();
      value.uniquePhiUsers().forEach(this::addRequiredValue);
      for (Instruction instruction : value.uniqueUsers()) {
        DeadInstructionResult result = instruction.canBeDeadCode(appView, code);
        if (result.isNotDead()) {
          recordValueAndDependentsAreNotDead(valueNumber);
          return false;
        }
        if (result.isMaybeDead()) {
          result.getValuesRequiredToBeDead().forEach(this::addRequiredValue);
        }
        if (instruction.hasOutValue()) {
          addRequiredValue(instruction.outValue());
        }
      }

      int retainedRequiredValues = 0;
      for (int index = 0; index < requiredValuesSize; index++) {
        int requiredValueNumber = requiredValues[index];
        if (hasProvenThatValueIsNotDead(requiredValueNumber)) {
          recordValueAndDependentsAreNotDead(valueNumber);
          return false;
        }
        if (needsToProveThatValueIsDead(valueNumber, requiredValueNumber)) {
          requiredValues[retainedRequiredValues++] = requiredValueNumber;
        }
      }
      requiredValuesSize = retainedRequiredValues;

      if (requiredValuesSize == 0) {
        // We have now proven that this value is dead.
        recordValueIsDeadAndPropagateToDependents(valueNumber);
      } else {
        // Record the current value as a dependent of each value required to be dead.
        remainingDependencies[valueNumber] = requiredValuesSize;
        for (int index = 0; index < requiredValuesSize; index++) {
          int requiredValueNumber = requiredValues[index];
          addDependenceEdge(valueNumber, requiredValueNumber);
          foundCycle |= seenEpochs[requiredValueNumber] == currentEpoch;
        }

        // Continue the analysis of the dependents.
        for (int index = 0; index < requiredValuesSize; index++) {
          addToWorkQueueIfNotSeen(requiredValues[index]);
        }
      }
    }
    return true;
  }

  private boolean hasProvenThatValueIsNotDead(int valueRequiredToBeDead) {
    return analysisCache[valueRequiredToBeDead] == NOT_DEAD;
  }

  private boolean needsToProveThatValueIsDead(int value, int valueRequiredToBeDead) {
    // No need to record that the deadness of a values relies on its own removal.
    assert !hasProvenThatValueIsNotDead(valueRequiredToBeDead);
    return valueRequiredToBeDead != value && analysisCache[valueRequiredToBeDead] == UNKNOWN;
  }

  private void recordValueIsDeadAndPropagateToDependents(int value) {
    int propagationQueueHead = 0;
    int propagationQueueTail = 1;
    propagationQueue[0] = value;
    while (propagationQueueHead < propagationQueueTail) {
      int current = propagationQueue[propagationQueueHead++];
      assert remainingDependencies[current] == 0;
      recordValueIsDead(current);

      // Continue processing of new leaves.
      for (int edge = getFirstDependentEdge(current); edge >= 0; edge = nextDependentEdges[edge]) {
        int dependent = dependentEdgeTargets[edge];
        int remaining = --remainingDependencies[dependent];
        assert remaining >= 0;
        if (remaining == 0) {
          propagationQueue = ensureCapacity(propagationQueue, propagationQueueTail + 1);
          propagationQueue[propagationQueueTail++] = dependent;
        }
      }
    }
  }

  private void recordValueIsDead(int value) {
    byte existingResult = analysisCache[value];
    analysisCache[value] = DEAD;
    assert existingResult == UNKNOWN || existingResult == DEAD;
  }

  private void recordValueAndDependentsAreNotDead(int value) {
    recordValueIsNotDead(value);
    recordDependentsAreNotDead(value);
  }

  private void recordValueIsNotDead(int value) {
    byte existingResult = analysisCache[value];
    analysisCache[value] = NOT_DEAD;
    assert existingResult == UNKNOWN || existingResult == NOT_DEAD;
  }

  private void recordDependentsAreNotDead(int value) {
    int propagationQueueHead = 0;
    int propagationQueueTail = 1;
    propagationQueue[0] = value;
    while (propagationQueueHead < propagationQueueTail) {
      int current = propagationQueue[propagationQueueHead++];
      for (int edge = getFirstDependentEdge(current); edge >= 0; edge = nextDependentEdges[edge]) {
        int dependent = dependentEdgeTargets[edge];
        if (analysisCache[dependent] != NOT_DEAD) {
          recordValueIsNotDead(dependent);
          propagationQueue = ensureCapacity(propagationQueue, propagationQueueTail + 1);
          propagationQueue[propagationQueueTail++] = dependent;
        }
      }
    }
  }

  private void startQuery(Value value) {
    if (++currentEpoch == 0) {
      Arrays.fill(seenEpochs, 0);
      Arrays.fill(firstDependentEdgeEpochs, 0);
      currentEpoch = 1;
    }
    workQueueHead = 0;
    workQueueTail = 0;
    dependentEdgesSize = 0;
    foundCycle = false;
    int valueNumber = getValueNumber(value);
    valuesByNumber[valueNumber] = value;
    addToWorkQueueIfNotSeen(valueNumber);
  }

  private void startRequiredValues() {
    if (++currentRequiredEpoch == 0) {
      Arrays.fill(requiredEpochs, 0);
      currentRequiredEpoch = 1;
    }
    requiredValuesSize = 0;
  }

  private void addRequiredValue(Value value) {
    int valueNumber = getValueNumber(value);
    valuesByNumber[valueNumber] = value;
    if (requiredEpochs[valueNumber] != currentRequiredEpoch) {
      requiredEpochs[valueNumber] = currentRequiredEpoch;
      requiredValues = ensureCapacity(requiredValues, requiredValuesSize + 1);
      requiredValues[requiredValuesSize++] = valueNumber;
    }
  }

  private void addToWorkQueueIfNotSeen(int valueNumber) {
    if (seenEpochs[valueNumber] != currentEpoch) {
      seenEpochs[valueNumber] = currentEpoch;
      remainingDependencies[valueNumber] = 0;
      workQueue = ensureCapacity(workQueue, workQueueTail + 1);
      workQueue[workQueueTail++] = valueNumber;
    }
  }

  /**
   * Records that the removal of {@param value} depends on the removal of {@param
   * valueRequiredToBeDead} by adding a reverse edge from {@param valueRequiredToBeDead} to {@param
   * value}.
   */
  private void addDependenceEdge(int value, int valueRequiredToBeDead) {
    int firstDependentEdge = getFirstDependentEdge(valueRequiredToBeDead);
    dependentEdgeTargets = ensureCapacity(dependentEdgeTargets, dependentEdgesSize + 1);
    nextDependentEdges = ensureCapacity(nextDependentEdges, dependentEdgesSize + 1);
    dependentEdgeTargets[dependentEdgesSize] = value;
    nextDependentEdges[dependentEdgesSize] = firstDependentEdge;
    firstDependentEdgeEpochs[valueRequiredToBeDead] = currentEpoch;
    firstDependentEdges[valueRequiredToBeDead] = dependentEdgesSize++;
  }

  private int getFirstDependentEdge(int value) {
    return firstDependentEdgeEpochs[value] == currentEpoch ? firstDependentEdges[value] : -1;
  }

  private int getValueNumber(Value value) {
    int valueNumber = value.getNumber();
    assert valueNumber >= 0;
    ensureValueCapacity(valueNumber + 1);
    return valueNumber;
  }

  private void ensureValueCapacity(int minimumCapacity) {
    if (minimumCapacity <= analysisCache.length) {
      return;
    }
    int newCapacity = Math.max(minimumCapacity, analysisCache.length * 2);
    analysisCache = Arrays.copyOf(analysisCache, newCapacity);
    seenEpochs = Arrays.copyOf(seenEpochs, newCapacity);
    requiredEpochs = Arrays.copyOf(requiredEpochs, newCapacity);
    firstDependentEdgeEpochs = Arrays.copyOf(firstDependentEdgeEpochs, newCapacity);
    firstDependentEdges = Arrays.copyOf(firstDependentEdges, newCapacity);
    remainingDependencies = Arrays.copyOf(remainingDependencies, newCapacity);
    valuesByNumber = Arrays.copyOf(valuesByNumber, newCapacity);
  }

  private static int[] ensureCapacity(int[] array, int minimumCapacity) {
    return minimumCapacity <= array.length
        ? array
        : Arrays.copyOf(array, Math.max(minimumCapacity, array.length * 2));
  }

  private boolean verifySeenValuesAreDead() {
    for (int index = 0; index < workQueueTail; index++) {
      assert analysisCache[workQueue[index]] == DEAD;
    }
    return true;
  }

  private void clearQueryValueReferences() {
    for (int index = 0; index < workQueueTail; index++) {
      valuesByNumber[workQueue[index]] = null;
    }
    for (int index = 0; index < requiredValuesSize; index++) {
      valuesByNumber[requiredValues[index]] = null;
    }
  }
}
