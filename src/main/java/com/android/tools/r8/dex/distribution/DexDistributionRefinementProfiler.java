// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex.distribution;

import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodHandle;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

class DexDistributionRefinementProfiler {

  enum CountScanKind {
    SOURCE_SAVINGS,
    TARGET_COST
  }

  private static final String PROPERTY = "com.android.tools.r8.benchmark.dexDistributionProfile";
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
  private static final String[] ITEM_KIND_NAMES = {
    "call-site", "field", "method", "method-handle", "proto", "string", "type"
  };
  private static final DexDistributionRefinementProfiler INSTANCE = createIfEnabled();

  private final Path output;
  private final LongAdder partitions = new LongAdder();
  private final LongAdder containerDexPartitions = new LongAdder();
  private final LongAdder passes = new LongAdder();
  private final LongAdder refinedFiles = new LongAdder();
  private final LongAdder assessedClasses = new LongAdder();
  private final LongAdder collectedClasses = new LongAdder();
  private final LongAdder collectedItems = new LongAdder();
  private final LongAdder targetFilesConsidered = new LongAdder();
  private final LongAdder targetCapacityRejections = new LongAdder();
  private final LongAdder beneficialTargets = new LongAdder();
  private final LongAdder sourceSavingsScans = new LongAdder();
  private final LongAdder sourceSavingsItemVisits = new LongAdder();
  private final LongAdder sourceSavingsSkippedStrings = new LongAdder();
  private final LongAdder targetCostScans = new LongAdder();
  private final LongAdder targetCostItemVisits = new LongAdder();
  private final LongAdder targetCostSkippedStrings = new LongAdder();
  private final LongAdder initialCapacityScans = new LongAdder();
  private final LongAdder revalidationCapacityScans = new LongAdder();
  private final LongAdder capacityItemVisits = new LongAdder();
  private final LongAdder capacityRelevantItemVisits = new LongAdder();
  private final LongAdder capacityRejectedScans = new LongAdder();
  private final LongAdder capacityAvoidableTailVisits = new LongAdder();
  private final LongAdder successfulMoves = new LongAdder();
  private final LongAdder failedMoves = new LongAdder();
  private final AtomicLongArray itemKinds = new AtomicLongArray(ITEM_KIND_NAMES.length);
  private final AtomicLongArray partitionFileHistogram = new AtomicLongArray(BUCKET_NAMES.length);
  private final AtomicLongArray refinedFileClassHistogram =
      new AtomicLongArray(BUCKET_NAMES.length);
  private final AtomicLongArray collectedItemHistogram = new AtomicLongArray(BUCKET_NAMES.length);
  private final AtomicLongArray sourceSavingsHistogram = new AtomicLongArray(BUCKET_NAMES.length);
  private final AtomicLongArray beneficialTargetHistogram =
      new AtomicLongArray(BUCKET_NAMES.length);
  private final AtomicLongArray firstOverflowHistogram = new AtomicLongArray(BUCKET_NAMES.length);

  private DexDistributionRefinementProfiler(Path output) {
    this.output = output;
    Runtime.getRuntime()
        .addShutdownHook(new Thread(this::writeProfile, "r8-dex-distribution-profiler"));
  }

  static DexDistributionRefinementProfiler getIfEnabled() {
    return INSTANCE;
  }

  private static DexDistributionRefinementProfiler createIfEnabled() {
    String outputProperty = System.getProperty(PROPERTY, "");
    return outputProperty.isEmpty()
        ? null
        : new DexDistributionRefinementProfiler(Paths.get(outputProperty));
  }

  void recordPartition(int fileCount, boolean enableContainerDex) {
    partitions.increment();
    if (enableContainerDex) {
      containerDexPartitions.increment();
    }
    partitionFileHistogram.incrementAndGet(bucket(fileCount));
  }

  void recordPass() {
    passes.increment();
  }

  void recordRefinedFile(int classCount) {
    refinedFiles.increment();
    assessedClasses.add(classCount);
    refinedFileClassHistogram.incrementAndGet(bucket(classCount));
  }

  void recordCollectedItems(Set<DexItem> items) {
    collectedClasses.increment();
    collectedItems.add(items.size());
    collectedItemHistogram.incrementAndGet(bucket(items.size()));
    for (DexItem item : items) {
      itemKinds.incrementAndGet(itemKind(item));
    }
  }

  void recordCountScan(
      CountScanKind kind, int itemCount, int skippedStrings, int matchingReferences) {
    if (kind == CountScanKind.SOURCE_SAVINGS) {
      sourceSavingsScans.increment();
      sourceSavingsItemVisits.add(itemCount - skippedStrings);
      sourceSavingsSkippedStrings.add(skippedStrings);
      sourceSavingsHistogram.incrementAndGet(bucket(matchingReferences));
    } else {
      targetCostScans.increment();
      targetCostItemVisits.add(itemCount - skippedStrings);
      targetCostSkippedStrings.add(skippedStrings);
    }
  }

  void recordTargetSearch(int targetCount, int capacityRejections, int beneficialTargetCount) {
    targetFilesConsidered.add(targetCount);
    targetCapacityRejections.add(capacityRejections);
    beneficialTargets.add(beneficialTargetCount);
    beneficialTargetHistogram.incrementAndGet(bucket(beneficialTargetCount));
  }

  void recordCapacityScan(
      boolean revalidation, int itemCount, int relevantItems, int firstOverflowAt) {
    if (revalidation) {
      revalidationCapacityScans.increment();
    } else {
      initialCapacityScans.increment();
    }
    capacityItemVisits.add(itemCount);
    capacityRelevantItemVisits.add(relevantItems);
    if (firstOverflowAt > 0) {
      capacityRejectedScans.increment();
      capacityAvoidableTailVisits.add(itemCount - firstOverflowAt);
      firstOverflowHistogram.incrementAndGet(bucket(firstOverflowAt));
    }
  }

  void recordMove(boolean successful) {
    if (successful) {
      successfulMoves.increment();
    } else {
      failedMoves.increment();
    }
  }

  private static int itemKind(DexItem item) {
    if (item instanceof DexCallSite) {
      return 0;
    }
    if (item instanceof DexField) {
      return 1;
    }
    if (item instanceof DexMethod) {
      return 2;
    }
    if (item instanceof DexMethodHandle) {
      return 3;
    }
    if (item instanceof DexProto) {
      return 4;
    }
    if (item instanceof DexString) {
      return 5;
    }
    assert item instanceof DexType;
    return 6;
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
        writeMetric(writer, "partitions", partitions.sum());
        writeMetric(writer, "container-dex-partitions", containerDexPartitions.sum());
        writeMetric(writer, "passes", passes.sum());
        writeMetric(writer, "refined-files", refinedFiles.sum());
        writeMetric(writer, "assessed-classes", assessedClasses.sum());
        writeMetric(writer, "collected-classes", collectedClasses.sum());
        writeMetric(writer, "collected-items", collectedItems.sum());
        writeMetric(writer, "target-files-considered", targetFilesConsidered.sum());
        writeMetric(writer, "target-capacity-rejections", targetCapacityRejections.sum());
        writeMetric(writer, "beneficial-targets", beneficialTargets.sum());
        writeMetric(writer, "source-savings-scans", sourceSavingsScans.sum());
        writeMetric(writer, "source-savings-item-visits", sourceSavingsItemVisits.sum());
        writeMetric(writer, "source-savings-skipped-strings", sourceSavingsSkippedStrings.sum());
        writeMetric(writer, "target-cost-scans", targetCostScans.sum());
        writeMetric(writer, "target-cost-item-visits", targetCostItemVisits.sum());
        writeMetric(writer, "target-cost-skipped-strings", targetCostSkippedStrings.sum());
        writeMetric(writer, "initial-capacity-scans", initialCapacityScans.sum());
        writeMetric(writer, "revalidation-capacity-scans", revalidationCapacityScans.sum());
        writeMetric(writer, "capacity-item-visits", capacityItemVisits.sum());
        writeMetric(writer, "capacity-relevant-item-visits", capacityRelevantItemVisits.sum());
        writeMetric(writer, "capacity-rejected-scans", capacityRejectedScans.sum());
        writeMetric(writer, "capacity-avoidable-tail-visits", capacityAvoidableTailVisits.sum());
        writeMetric(writer, "successful-moves", successfulMoves.sum());
        writeMetric(writer, "failed-moves", failedMoves.sum());
        for (int i = 0; i < ITEM_KIND_NAMES.length; i++) {
          writeRow(writer, "item-kind", ITEM_KIND_NAMES[i], itemKinds.get(i));
        }
        writeHistogram(writer, "partition-files", partitionFileHistogram);
        writeHistogram(writer, "refined-file-classes", refinedFileClassHistogram);
        writeHistogram(writer, "collected-items", collectedItemHistogram);
        writeHistogram(writer, "source-savings", sourceSavingsHistogram);
        writeHistogram(writer, "beneficial-targets", beneficialTargetHistogram);
        writeHistogram(writer, "first-overflow-at", firstOverflowHistogram);
      }
    } catch (IOException exception) {
      System.err.println("Failed to write DEX distribution profile: " + exception.getMessage());
    }
  }

  private static void writeMetric(BufferedWriter writer, String name, long value)
      throws IOException {
    writeRow(writer, "metric", name, value);
  }

  private static void writeHistogram(BufferedWriter writer, String name, AtomicLongArray histogram)
      throws IOException {
    for (int i = 0; i < BUCKET_NAMES.length; i++) {
      writer.write("histogram\t");
      writer.write(name);
      writer.write('\t');
      writer.write(BUCKET_NAMES[i]);
      writer.write('\t');
      writer.write(Long.toString(histogram.get(i)));
      writer.newLine();
    }
  }

  private static void writeRow(BufferedWriter writer, String kind, String name, long value)
      throws IOException {
    writer.write(kind);
    writer.write('\t');
    writer.write(name);
    writer.write('\t');
    writer.write(Long.toString(value));
    writer.newLine();
  }
}
