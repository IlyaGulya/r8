// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.benchmarks.agent;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

public final class AllocatorTimingRecorder {

  private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
  private static final LongAdder INVOCATIONS = new LongAdder();
  private static final LongAdder WALL_NANOS = new LongAdder();
  private static final LongAdder CPU_NANOS = new LongAdder();
  private static volatile Path output;
  private static Set<String> includedHolders = Collections.emptySet();

  static void configure(String argument) {
    if (argument == null || argument.isEmpty()) {
      throw new IllegalArgumentException("allocator timing agent requires an output path");
    }
    String[] options = argument.split(";", -1);
    output = Paths.get(options[0]).toAbsolutePath();
    for (int index = 1; index < options.length; index++) {
      String option = options[index];
      if (!option.startsWith("holders=")) {
        throw new IllegalArgumentException("unknown allocator timing agent option: " + option);
      }
      Path holdersPath = Paths.get(option.substring("holders=".length())).toAbsolutePath();
      try {
        includedHolders = new HashSet<>(Files.readAllLines(holdersPath, StandardCharsets.UTF_8));
      } catch (IOException exception) {
        throw new IllegalArgumentException(
            "failed to read holder filter " + holdersPath, exception);
      }
      includedHolders.remove("");
      if (includedHolders.isEmpty()) {
        throw new IllegalArgumentException("holder filter is empty: " + holdersPath);
      }
    }
    if (THREADS.isThreadCpuTimeSupported() && !THREADS.isThreadCpuTimeEnabled()) {
      THREADS.setThreadCpuTimeEnabled(true);
    }
    Runtime.getRuntime()
        .addShutdownHook(new Thread(AllocatorTimingRecorder::write, "allocator-timing"));
  }

  public static long currentThreadCpuTime() {
    return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1;
  }

  public static void record(String holder, long wallStart, long cpuStart) {
    if (!includedHolders.isEmpty() && !includedHolders.contains(holder)) {
      return;
    }
    long cpuEnd = currentThreadCpuTime();
    INVOCATIONS.increment();
    WALL_NANOS.add(System.nanoTime() - wallStart);
    if (cpuStart >= 0 && cpuEnd >= cpuStart) {
      CPU_NANOS.add(cpuEnd - cpuStart);
    }
  }

  private static void write() {
    Path destination = output;
    if (destination == null) {
      return;
    }
    String json =
        "{\"schema_version\":1,\"invocations\":"
            + INVOCATIONS.sum()
            + ",\"wall_nanos\":"
            + WALL_NANOS.sum()
            + ",\"cpu_nanos\":"
            + CPU_NANOS.sum()
            + "}\n";
    Path temporary = destination.resolveSibling(destination.getFileName() + ".partial");
    try {
      Path parent = destination.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(temporary, json.getBytes(StandardCharsets.UTF_8));
      try {
        Files.move(
            temporary,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException ignored) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException exception) {
      throw new RuntimeException("failed to write allocator timing", exception);
    }
  }

  private AllocatorTimingRecorder() {}
}
