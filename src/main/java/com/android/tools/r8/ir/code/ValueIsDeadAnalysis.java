// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import static com.android.tools.r8.utils.MapUtils.ignoreKey;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.ir.optimize.DeadCodeRemover.DeadInstructionResult;
import com.android.tools.r8.utils.BooleanBox;
import com.android.tools.r8.utils.MapUtils;
import com.android.tools.r8.utils.WorkList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

public class ValueIsDeadAnalysis {

  private enum ValueIsDeadResult {
    DEAD,
    NOT_DEAD;

    boolean isDead() {
      return this == DEAD;
    }

    boolean isNotDead() {
      return this == NOT_DEAD;
    }
  }

  private final AppView<?> appView;
  private final IRCode code;

  private final Map<Value, ValueIsDeadResult> analysisCache = new IdentityHashMap<>();
  private final ValueIsDeadProfiler.MethodStats profileMethodStats;

  private int profileVisited;
  private int profileEdges;
  private int profileRawRequired;
  private int profileFilteredRequired;
  private int profileMaxRequired;
  private int profileDeadCacheHits;
  private int profileNotDeadCacheHits;
  private int profileReason;

  public ValueIsDeadAnalysis(AppView<?> appView, IRCode code) {
    this.appView = appView;
    this.code = code;
    profileMethodStats =
        ValueIsDeadProfiler.INSTANCE.isEnabled()
            ? ValueIsDeadProfiler.INSTANCE.getMethodStats(code.context().toSourceString())
            : null;
  }

  public boolean isDead(Value value) {
    boolean profile = profileMethodStats != null;
    if (profile) {
      resetProfileQuery();
    }
    // Totally unused values are trivially dead.
    if (value.isUnused()) {
      if (profile) {
        ValueIsDeadProfiler.INSTANCE.recordTrivialQuery(profileMethodStats);
      }
      return true;
    }
    // Create an is-dead dependence graph. If the deadness of value v depends on value u being dead,
    // a directed edge [v -> u] is added to the graph.
    //
    // This graph serves two purposes:
    // 1) If the analysis finds that `u` is *not* dead, then using the graph we can find all the
    //    values whose deadness depend (directly or indirectly) on `u` being dead, and mark them as
    //    being *not* dead in the analysis cache.
    // 2) If the analysis finds that `u` *is* dead, we can remove the node from the dependence graph
    //    (as it is necessarily a leaf), and repeatedly mark direct and indirect predecessors of `u`
    //    that have now become leaves as being dead in the analysis cache.
    WorkList<Value> worklist = WorkList.newIdentityWorkList(value);
    BooleanBox foundCycle = new BooleanBox();
    Value notDeadWitness = findNotDeadWitness(worklist, foundCycle);
    boolean isDead = Objects.isNull(notDeadWitness);
    if (isDead) {
      if (foundCycle.isTrue()) {
        for (Value deadValue : worklist.getSeenSet()) {
          recordValueIsDead(deadValue);
        }
      } else {
        assert worklist.getSeenSet().stream()
            .allMatch(deadValue -> analysisCache.get(deadValue) == ValueIsDeadResult.DEAD);
      }
    }
    if (profile) {
      ValueIsDeadProfiler.INSTANCE.recordQuery(
          profileMethodStats,
          isDead,
          foundCycle.isTrue(),
          profileVisited,
          worklist.getSeenSet().size(),
          profileEdges,
          profileRawRequired,
          profileFilteredRequired,
          profileMaxRequired,
          profileDeadCacheHits,
          profileNotDeadCacheHits,
          profileReason);
    }
    return isDead;
  }

  private void resetProfileQuery() {
    profileVisited = 0;
    profileEdges = 0;
    profileRawRequired = 0;
    profileFilteredRequired = 0;
    profileMaxRequired = 0;
    profileDeadCacheHits = 0;
    profileNotDeadCacheHits = 0;
    profileReason = ValueIsDeadProfiler.REASON_EXHAUSTED;
  }

  public boolean hasDeadPhi(BasicBlock block) {
    return Iterables.any(block.getPhis(), this::isDead);
  }

  private Value findNotDeadWitness(WorkList<Value> worklist, BooleanBox foundCycle) {
    DependenceGraph dependenceGraph = new DependenceGraph();
    while (worklist.hasNext()) {
      Value value = worklist.next();
      if (profileMethodStats != null) {
        profileVisited++;
      }

      // The first time we visit a value we have not yet added any outgoing edges to the dependence
      // graph.
      assert dependenceGraph.isLeaf(value);

      // Lookup if we have already analyzed the deadness of this value.
      ValueIsDeadResult cacheResult = analysisCache.get(value);
      if (cacheResult != null) {
        // If it is dead, then continue the search for a non-dead dependent. Otherwise this value is
        // a witness that the analysis failed.
        if (cacheResult.isDead()) {
          if (profileMethodStats != null) {
            profileDeadCacheHits++;
          }
          continue;
        } else {
          if (profileMethodStats != null) {
            profileNotDeadCacheHits++;
            profileReason = ValueIsDeadProfiler.REASON_NOT_DEAD_CACHE;
          }
          recordDependentsAreNotDead(value, dependenceGraph);
          return value;
        }
      }

      // If the value has debug users we cannot eliminate it since it represents a value in a local
      // variable that should be visible in the debugger.
      if (value.hasDebugUsers()) {
        if (profileMethodStats != null) {
          profileReason = ValueIsDeadProfiler.REASON_DEBUG_USER;
        }
        recordValueAndDependentsAreNotDead(value, dependenceGraph);
        return value;
      }

      Set<Value> valuesRequiredToBeDead = new LinkedHashSet<>(value.uniquePhiUsers());
      for (Instruction instruction : value.uniqueUsers()) {
        DeadInstructionResult result = instruction.canBeDeadCode(appView, code);
        if (result.isNotDead()) {
          if (profileMethodStats != null) {
            profileReason = ValueIsDeadProfiler.REASON_SIDE_EFFECT;
          }
          recordValueAndDependentsAreNotDead(value, dependenceGraph);
          return value;
        }
        if (result.isMaybeDead()) {
          result.getValuesRequiredToBeDead().forEach(valuesRequiredToBeDead::add);
        }
        if (instruction.hasOutValue()) {
          valuesRequiredToBeDead.add(instruction.outValue());
        }
      }
      if (profileMethodStats != null) {
        int requiredSize = valuesRequiredToBeDead.size();
        profileRawRequired += requiredSize;
        profileMaxRequired = Math.max(profileMaxRequired, requiredSize);
      }

      Iterator<Value> valuesRequiredToBeDeadIterator = valuesRequiredToBeDead.iterator();
      while (valuesRequiredToBeDeadIterator.hasNext()) {
        Value valueRequiredToBeDead = valuesRequiredToBeDeadIterator.next();
        if (hasProvenThatValueIsNotDead(valueRequiredToBeDead)) {
          if (profileMethodStats != null) {
            profileReason = ValueIsDeadProfiler.REASON_REQUIRED_NOT_DEAD;
          }
          recordValueAndDependentsAreNotDead(value, dependenceGraph);
          return value;
        }
        if (!needsToProveThatValueIsDead(value, valueRequiredToBeDead)) {
          valuesRequiredToBeDeadIterator.remove();
        }
      }
      if (profileMethodStats != null) {
        profileFilteredRequired += valuesRequiredToBeDead.size();
      }

      if (valuesRequiredToBeDead.isEmpty()) {
        // We have now proven that this value is dead.
        recordValueIsDeadAndPropagateToDependents(value, dependenceGraph);
      } else {
        // Record the current value as a dependent of each value required to be dead.
        for (Value valueRequiredToBeDead : valuesRequiredToBeDead) {
          if (profileMethodStats != null) {
            profileEdges++;
          }
          dependenceGraph.addDependenceEdge(value, valueRequiredToBeDead);
          foundCycle.or(worklist.isSeen(valueRequiredToBeDead));
        }

        // Continue the analysis of the dependents.
        worklist.addIfNotSeen(valuesRequiredToBeDead);
      }
    }
    return null;
  }

  private boolean hasProvenThatValueIsNotDead(Value valueRequiredToBeDead) {
    return analysisCache.get(valueRequiredToBeDead) == ValueIsDeadResult.NOT_DEAD;
  }

  private boolean needsToProveThatValueIsDead(Value value, Value valueRequiredToBeDead) {
    // No need to record that the deadness of a values relies on its own removal.
    assert !hasProvenThatValueIsNotDead(valueRequiredToBeDead);
    return valueRequiredToBeDead != value && !analysisCache.containsKey(valueRequiredToBeDead);
  }

  private void recordValueIsDeadAndPropagateToDependents(
      Value value, DependenceGraph dependenceGraph) {
    WorkList<Value> worklist = WorkList.newIdentityWorkList(value);
    while (worklist.hasNext()) {
      Value current = worklist.next();
      recordValueIsDead(current);

      // This value is now proven to be dead, thus there is no need to keep track of its successors.
      dependenceGraph.unlinkSuccessors(current);

      // Continue processing of new leaves.
      for (Value dependent : dependenceGraph.removeLeaf(current)) {
        if (dependenceGraph.isLeaf(dependent)) {
          worklist.addIfNotSeen(dependent);
        }
      }
    }
  }

  private void recordValueIsDead(Value value) {
    ValueIsDeadResult existingResult = analysisCache.put(value, ValueIsDeadResult.DEAD);
    assert existingResult == null || existingResult.isDead();
  }

  private void recordValueAndDependentsAreNotDead(Value value, DependenceGraph dependenceGraph) {
    recordValueIsNotDead(value, dependenceGraph);
    recordDependentsAreNotDead(value, dependenceGraph);
  }

  private void recordValueIsNotDead(Value value, DependenceGraph dependenceGraph) {
    // This value is now proven to be dead, thus there is no need to keep track of its successors.
    dependenceGraph.unlinkSuccessors(value);
    ValueIsDeadResult existingResult = analysisCache.put(value, ValueIsDeadResult.NOT_DEAD);
    assert existingResult == null || existingResult.isNotDead();
  }

  private void recordDependentsAreNotDead(Value value, DependenceGraph dependenceGraph) {
    WorkList<Value> worklist = WorkList.newIdentityWorkList(value);
    while (worklist.hasNext()) {
      Value current = worklist.next();
      for (Value dependent : dependenceGraph.removeLeaf(current)) {
        recordValueIsNotDead(dependent, dependenceGraph);
        worklist.addIfNotSeen(dependent);
      }
    }
  }

  private static class DependenceGraph {

    private final Map<Value, Set<Value>> successors = new IdentityHashMap<>();
    private final Map<Value, Set<Value>> predecessors = new IdentityHashMap<>();

    /**
     * Records that the removal of {@param value} depends on the removal of {@param
     * valueRequiredToBeDead} by adding an edge from {@param value} to {@param
     * valueRequiredToBeDead} in this graph.
     */
    void addDependenceEdge(Value value, Value valueRequiredToBeDead) {
      successors
          .computeIfAbsent(value, ignoreKey(Sets::newIdentityHashSet))
          .add(valueRequiredToBeDead);
      predecessors
          .computeIfAbsent(valueRequiredToBeDead, ignoreKey(Sets::newIdentityHashSet))
          .add(value);
    }

    Set<Value> removeLeaf(Value value) {
      assert isLeaf(value);
      Set<Value> dependents = MapUtils.removeOrDefault(predecessors, value, Collections.emptySet());
      for (Value dependent : dependents) {
        Set<Value> dependentSuccessors = successors.get(dependent);
        boolean removed = dependentSuccessors.remove(value);
        assert removed;
        if (dependentSuccessors.isEmpty()) {
          successors.remove(dependent);
        }
      }
      return dependents;
    }

    void unlinkSuccessors(Value value) {
      Set<Value> valueSuccessors =
          MapUtils.removeOrDefault(successors, value, Collections.emptySet());
      for (Value successor : valueSuccessors) {
        Set<Value> successorPredecessors = predecessors.get(successor);
        boolean removed = successorPredecessors.remove(value);
        assert removed;
        if (successorPredecessors.isEmpty()) {
          predecessors.remove(successor);
        }
      }
    }

    boolean isLeaf(Value value) {
      return !successors.containsKey(value);
    }
  }

  private static class ValueIsDeadProfiler {

    static final int REASON_EXHAUSTED = 0;
    static final int REASON_NOT_DEAD_CACHE = 1;
    static final int REASON_DEBUG_USER = 2;
    static final int REASON_SIDE_EFFECT = 3;
    static final int REASON_REQUIRED_NOT_DEAD = 4;

    private static final String[] REASON_NAMES = {
      "exhausted", "not-dead-cache", "debug-user", "side-effect", "required-not-dead"
    };
    private static final String[] BUCKET_NAMES = {
      "0",
      "1",
      "2",
      "3-4",
      "5-8",
      "9-16",
      "17-32",
      "33-64",
      "65-128",
      "129-256",
      "257-512",
      "513-1024",
      "1025-2048",
      "2049-4096",
      "4097+"
    };

    static final ValueIsDeadProfiler INSTANCE = new ValueIsDeadProfiler();

    private final Path output;
    private final MethodStats global = new MethodStats("<global>");
    private final ConcurrentHashMap<String, MethodStats> methods = new ConcurrentHashMap<>();
    private final AtomicLongArray visitedHistogram = new AtomicLongArray(BUCKET_NAMES.length);
    private final AtomicLongArray discoveredHistogram = new AtomicLongArray(BUCKET_NAMES.length);
    private final AtomicLongArray edgeHistogram = new AtomicLongArray(BUCKET_NAMES.length);
    private final AtomicLongArray maxRequiredHistogram = new AtomicLongArray(BUCKET_NAMES.length);
    private final AtomicLongArray reasons = new AtomicLongArray(REASON_NAMES.length);

    private ValueIsDeadProfiler() {
      String outputProperty =
          System.getProperty("com.android.tools.r8.benchmark.valueIsDeadProfile", "");
      output = outputProperty.isEmpty() ? null : Paths.get(outputProperty);
      if (output != null) {
        Runtime.getRuntime()
            .addShutdownHook(new Thread(this::writeProfile, "r8-value-is-dead-profiler"));
      }
    }

    boolean isEnabled() {
      return output != null;
    }

    MethodStats getMethodStats(String method) {
      return methods.computeIfAbsent(method, MethodStats::new);
    }

    void recordTrivialQuery(MethodStats method) {
      global.recordTrivialQuery();
      method.recordTrivialQuery();
    }

    void recordQuery(
        MethodStats method,
        boolean isDead,
        boolean foundCycle,
        int visited,
        int discovered,
        int edges,
        int rawRequired,
        int filteredRequired,
        int maxRequired,
        int deadCacheHits,
        int notDeadCacheHits,
        int reason) {
      global.recordQuery(
          isDead,
          foundCycle,
          visited,
          discovered,
          edges,
          rawRequired,
          filteredRequired,
          maxRequired,
          deadCacheHits,
          notDeadCacheHits);
      method.recordQuery(
          isDead,
          foundCycle,
          visited,
          discovered,
          edges,
          rawRequired,
          filteredRequired,
          maxRequired,
          deadCacheHits,
          notDeadCacheHits);
      visitedHistogram.incrementAndGet(bucket(visited));
      discoveredHistogram.incrementAndGet(bucket(discovered));
      edgeHistogram.incrementAndGet(bucket(edges));
      maxRequiredHistogram.incrementAndGet(bucket(maxRequired));
      reasons.incrementAndGet(reason);
    }

    private static int bucket(int value) {
      if (value <= 2) {
        return value;
      }
      int bucket = 3;
      int upper = 4;
      while (value > upper && bucket < BUCKET_NAMES.length - 1) {
        upper *= 2;
        bucket++;
      }
      return bucket;
    }

    private void writeProfile() {
      try {
        Path parent = output.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
          writeStats(writer, "summary", global);
          for (int reason = 0; reason < REASON_NAMES.length; reason++) {
            writeRow(writer, "reason", REASON_NAMES[reason], Long.toString(reasons.get(reason)));
          }
          writeHistogram(writer, "visited", visitedHistogram);
          writeHistogram(writer, "discovered", discoveredHistogram);
          writeHistogram(writer, "edges", edgeHistogram);
          writeHistogram(writer, "max-required", maxRequiredHistogram);

          List<MethodStats> sortedMethods = new ArrayList<>(methods.values());
          sortedMethods.sort(
              Comparator.comparingLong(MethodStats::edgeCount)
                  .thenComparingLong(MethodStats::visitedCount)
                  .reversed());
          int methodCount = Math.min(sortedMethods.size(), 100);
          for (int index = 0; index < methodCount; index++) {
            writeStats(writer, "method", sortedMethods.get(index));
          }
        }
      } catch (IOException exception) {
        System.err.println("Failed to write ValueIsDead profile to " + output);
        exception.printStackTrace();
      }
    }

    private static void writeHistogram(
        BufferedWriter writer, String metric, AtomicLongArray histogram) throws IOException {
      for (int bucket = 0; bucket < BUCKET_NAMES.length; bucket++) {
        writeRow(
            writer,
            "histogram",
            metric,
            BUCKET_NAMES[bucket],
            Long.toString(histogram.get(bucket)));
      }
    }

    private static void writeStats(BufferedWriter writer, String kind, MethodStats stats)
        throws IOException {
      writeRow(
          writer,
          kind,
          Long.toString(stats.queries.sum()),
          Long.toString(stats.trivial.sum()),
          Long.toString(stats.dead.sum()),
          Long.toString(stats.notDead.sum()),
          Long.toString(stats.cycles.sum()),
          Long.toString(stats.visited.sum()),
          Long.toString(stats.discovered.sum()),
          Long.toString(stats.edges.sum()),
          Long.toString(stats.rawRequired.sum()),
          Long.toString(stats.filteredRequired.sum()),
          Long.toString(stats.maxVisited.get()),
          Long.toString(stats.maxDiscovered.get()),
          Long.toString(stats.maxEdges.get()),
          Long.toString(stats.maxRequired.get()),
          Long.toString(stats.deadCacheHits.sum()),
          Long.toString(stats.notDeadCacheHits.sum()),
          stats.name);
    }

    private static void writeRow(BufferedWriter writer, String... values) throws IOException {
      for (int index = 0; index < values.length; index++) {
        if (index > 0) {
          writer.write('\t');
        }
        writer.write(values[index].replace('\t', ' ').replace('\n', ' '));
      }
      writer.newLine();
    }

    private static class MethodStats {

      final String name;
      final LongAdder queries = new LongAdder();
      final LongAdder trivial = new LongAdder();
      final LongAdder dead = new LongAdder();
      final LongAdder notDead = new LongAdder();
      final LongAdder cycles = new LongAdder();
      final LongAdder visited = new LongAdder();
      final LongAdder discovered = new LongAdder();
      final LongAdder edges = new LongAdder();
      final LongAdder rawRequired = new LongAdder();
      final LongAdder filteredRequired = new LongAdder();
      final LongAdder deadCacheHits = new LongAdder();
      final LongAdder notDeadCacheHits = new LongAdder();
      final AtomicLong maxVisited = new AtomicLong();
      final AtomicLong maxDiscovered = new AtomicLong();
      final AtomicLong maxEdges = new AtomicLong();
      final AtomicLong maxRequired = new AtomicLong();

      MethodStats(String name) {
        this.name = name;
      }

      void recordTrivialQuery() {
        queries.increment();
        trivial.increment();
        dead.increment();
      }

      void recordQuery(
          boolean isDead,
          boolean foundCycle,
          int visited,
          int discovered,
          int edges,
          int rawRequired,
          int filteredRequired,
          int maxRequired,
          int deadCacheHits,
          int notDeadCacheHits) {
        queries.increment();
        if (isDead) {
          dead.increment();
        } else {
          notDead.increment();
        }
        if (foundCycle) {
          cycles.increment();
        }
        this.visited.add(visited);
        this.discovered.add(discovered);
        this.edges.add(edges);
        this.rawRequired.add(rawRequired);
        this.filteredRequired.add(filteredRequired);
        this.deadCacheHits.add(deadCacheHits);
        this.notDeadCacheHits.add(notDeadCacheHits);
        updateMaximum(this.maxVisited, visited);
        updateMaximum(this.maxDiscovered, discovered);
        updateMaximum(this.maxEdges, edges);
        updateMaximum(this.maxRequired, maxRequired);
      }

      long edgeCount() {
        return edges.sum();
      }

      long visitedCount() {
        return visited.sum();
      }

      private static void updateMaximum(AtomicLong maximum, long value) {
        long current = maximum.get();
        while (value > current && !maximum.compareAndSet(current, value)) {
          current = maximum.get();
        }
      }
    }
  }
}
