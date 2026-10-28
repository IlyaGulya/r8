// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.benchmarks.regalloc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** Standalone deterministic replay runner for register-allocator layout traces. */
public final class LayoutReplayMain {

  private static final int MAGIC = 0x52384c54;
  private static final int TRACE_SCHEMA_VERSION = 1;
  private static final int RESULT_SCHEMA_VERSION = 1;

  private static final int ACTIVE = 0;
  private static final int INACTIVE = 1;

  private static final int LIST_NEW = 1;
  private static final int LIST_ADD = 2;
  private static final int LIST_REMOVE = 3;
  private static final int LIST_CLEAR = 4;
  private static final int ITERATOR_BEGIN = 5;
  private static final int ITERATOR_NEXT = 6;
  private static final int ITERATOR_REMOVE = 7;
  private static final int ITERATOR_END = 8;

  private static final int TABLE_NEW = 20;
  private static final int TABLE_SET = 21;
  private static final int TABLE_GET = 22;
  private static final int TABLE_SET_BLOCKED = 23;
  private static final int TABLE_IS_BLOCKED = 24;
  private static final int TABLE_HAS_TYPE = 25;
  private static final int TABLE_NEW_WRAPPER = 26;
  private static final int TABLE_SET_TEMP_BLOCKED = 27;
  private static final int TABLE_IS_TEMP_BLOCKED = 28;

  private static final int MAX_METHODS = 10_000_000;
  private static final int MAX_EVENT_INTS = 256 * 1024 * 1024;
  private static final int MAX_TABLES_PER_METHOD = 1_000_000;
  private static final int UNSET = Integer.MAX_VALUE;

  public static void main(String[] arguments) throws Exception {
    if (arguments.length == 1 && arguments[0].equals("--self-test")) {
      selfTest();
      return;
    }
    if (arguments.length == 2 && arguments[0].equals("--write-self-test-trace")) {
      writeSelfTestTrace(Path.of(arguments[1]));
      return;
    }
    Options options = Options.parse(arguments);
    Trace trace = Trace.read(options.trace);
    if (options.expectedFingerprint != null
        && !options.expectedFingerprint.equals(trace.fingerprint)) {
      throw new IllegalArgumentException(
          "Trace fingerprint differs: expected="
              + options.expectedFingerprint
              + " actual="
              + trace.fingerprint);
    }
    Result result = benchmark(trace, options);
    if (options.output == null) {
      System.out.print(result.toJson());
    } else {
      writeAtomically(options.output, result.toJson());
    }
  }

  private static Result benchmark(Trace trace, Options options) {
    ListFactory listFactory = ListFactory.create(options.listBackend);
    PositionFactory positionFactory = PositionFactory.create(options.positionBackend);

    long verificationChecksum = replayTrace(trace, listFactory, positionFactory);
    for (int iteration = 0; iteration < options.warmupIterations; iteration++) {
      long checksum = replayTrace(trace, listFactory, positionFactory);
      requireEqual(verificationChecksum, checksum, "warmup checksum");
    }

    long[] wallNanos = new long[options.measuredIterations];
    long[] cpuNanos = new long[options.measuredIterations];
    long[] allocatedBytes = new long[options.measuredIterations];
    Metrics metrics = Metrics.create();
    for (int iteration = 0; iteration < options.measuredIterations; iteration++) {
      long allocatedBefore = metrics.allocatedBytes();
      long cpuBefore = metrics.cpuNanos();
      long wallBefore = System.nanoTime();
      long checksum = replayTrace(trace, listFactory, positionFactory);
      wallNanos[iteration] = System.nanoTime() - wallBefore;
      cpuNanos[iteration] = metrics.delta(cpuBefore, metrics.cpuNanos());
      allocatedBytes[iteration] = metrics.delta(allocatedBefore, metrics.allocatedBytes());
      requireEqual(verificationChecksum, checksum, "measured checksum");
    }
    return new Result(
        trace,
        options,
        verificationChecksum,
        wallNanos,
        cpuNanos,
        allocatedBytes,
        metrics.cpuSupported,
        metrics.allocationSupported);
  }

  private static long replayTrace(
      Trace trace, ListFactory listFactory, PositionFactory positionFactory) {
    long checksum = 0xcbf29ce484222325L;
    for (MethodTrace method : trace.methods) {
      checksum = mix(checksum, method.assignmentHash);
      checksum = mix(checksum, replayMethod(method, listFactory, positionFactory));
    }
    return checksum;
  }

  private static long replayMethod(
      MethodTrace method, ListFactory listFactory, PositionFactory positionFactory) {
    IntList[] lists = new IntList[2];
    @SuppressWarnings({"unchecked", "rawtypes"})
    ArrayDeque<IntCursor>[] iterators = new ArrayDeque[] {new ArrayDeque<>(), new ArrayDeque<>()};
    PositionTable[] tables = new PositionTable[method.tableCount];
    int[] events = method.events;
    int cursor = 0;
    long checksum = mix(0xcbf29ce484222325L, method.intervalCount);
    while (cursor < events.length) {
      int opcode = events[cursor++];
      switch (opcode) {
        case LIST_NEW:
          {
            int kind = listKind(events[cursor++]);
            require(lists[kind] == null, "duplicate list " + kind);
            lists[kind] = listFactory.create();
            checksum = mix(checksum, opcode, kind);
            break;
          }
        case LIST_ADD:
          {
            int kind = listKind(events[cursor++]);
            int interval = interval(events[cursor++], method.intervalCount);
            list(lists, kind).add(interval);
            checksum = mix(checksum, opcode, kind, interval);
            break;
          }
        case LIST_REMOVE:
          {
            int kind = listKind(events[cursor++]);
            int interval = interval(events[cursor++], method.intervalCount);
            require(list(lists, kind).remove(interval), "list remove missed " + interval);
            checksum = mix(checksum, opcode, kind, interval);
            break;
          }
        case LIST_CLEAR:
          {
            int kind = listKind(events[cursor++]);
            list(lists, kind).clear();
            checksum = mix(checksum, opcode, kind);
            break;
          }
        case ITERATOR_BEGIN:
          {
            int kind = listKind(events[cursor++]);
            iterators[kind].push(list(lists, kind).iterator());
            checksum = mix(checksum, opcode, kind);
            break;
          }
        case ITERATOR_NEXT:
          {
            int kind = listKind(events[cursor++]);
            int expected = interval(events[cursor++], method.intervalCount);
            IntCursor iterator = iterator(iterators, kind);
            require(iterator.hasNext(), "iterator ended before " + expected);
            int actual = iterator.next();
            requireEqual(expected, actual, "iterator value");
            checksum = mix(checksum, opcode, kind, actual);
            break;
          }
        case ITERATOR_REMOVE:
          {
            int kind = listKind(events[cursor++]);
            int expected = interval(events[cursor++], method.intervalCount);
            IntCursor iterator = iterator(iterators, kind);
            requireEqual(expected, iterator.current(), "iterator remove value");
            iterator.remove();
            checksum = mix(checksum, opcode, kind, expected);
            break;
          }
        case ITERATOR_END:
          {
            int kind = listKind(events[cursor++]);
            IntCursor iterator = iterator(iterators, kind);
            require(!iterator.hasNext(), "iterator has unconsumed elements");
            iterators[kind].pop();
            checksum = mix(checksum, opcode, kind);
            break;
          }
        case TABLE_NEW:
          {
            int tableId = tableId(events[cursor++], tables.length);
            int limit = positive(events[cursor++], "table limit");
            require(tables[tableId] == null, "duplicate table " + tableId);
            tables[tableId] = positionFactory.create(limit);
            checksum = mix(checksum, opcode, tableId, limit);
            break;
          }
        case TABLE_SET:
          {
            int tableId = tableId(events[cursor++], tables.length);
            int index = events[cursor++];
            int value = events[cursor++];
            int typeMask = events[cursor++];
            table(tables, tableId).set(index, value, typeMask);
            checksum = mix(checksum, opcode, tableId, index, value, typeMask);
            break;
          }
        case TABLE_GET:
          {
            int tableId = tableId(events[cursor++], tables.length);
            int index = events[cursor++];
            int expected = events[cursor++];
            int actual = table(tables, tableId).get(index);
            requireEqual(expected, actual, "table get");
            checksum = mix(checksum, opcode, tableId, index, actual);
            break;
          }
        case TABLE_SET_BLOCKED:
          {
            int tableId = tableId(events[cursor++], tables.length);
            int index = events[cursor++];
            table(tables, tableId).setBlocked(index);
            checksum = mix(checksum, opcode, tableId, index);
            break;
          }
        case TABLE_IS_BLOCKED:
          {
            int tableId = tableId(events[cursor++], tables.length);
            int index = events[cursor++];
            boolean expected = booleanValue(events[cursor++]);
            boolean actual = table(tables, tableId).isBlocked(index);
            requireEqual(expected, actual, "table blocked");
            checksum = mix(checksum, opcode, tableId, index, actual ? 1 : 0);
            break;
          }
        case TABLE_HAS_TYPE:
          {
            int tableId = tableId(events[cursor++], tables.length);
            int index = events[cursor++];
            int type = events[cursor++];
            boolean expected = booleanValue(events[cursor++]);
            boolean actual = table(tables, tableId).hasType(index, type);
            requireEqual(expected, actual, "table type");
            checksum = mix(checksum, opcode, tableId, index, type, actual ? 1 : 0);
            break;
          }
        case TABLE_NEW_WRAPPER:
          {
            int tableId = tableId(events[cursor++], tables.length);
            int delegateId = tableId(events[cursor++], tables.length);
            int limit = positive(events[cursor++], "wrapper limit");
            require(tables[tableId] == null, "duplicate wrapper " + tableId);
            tables[tableId] = positionFactory.wrap(table(tables, delegateId), limit);
            checksum = mix(checksum, opcode, tableId, delegateId, limit);
            break;
          }
        case TABLE_SET_TEMP_BLOCKED:
          {
            int tableId = tableId(events[cursor++], tables.length);
            int index = events[cursor++];
            table(tables, tableId).setTemporarilyBlocked(index);
            checksum = mix(checksum, opcode, tableId, index);
            break;
          }
        case TABLE_IS_TEMP_BLOCKED:
          {
            int tableId = tableId(events[cursor++], tables.length);
            int index = events[cursor++];
            boolean expected = booleanValue(events[cursor++]);
            boolean actual = table(tables, tableId).isTemporarilyBlocked(index);
            requireEqual(expected, actual, "table temporarily blocked");
            checksum = mix(checksum, opcode, tableId, index, actual ? 1 : 0);
            break;
          }
        default:
          throw new IllegalStateException("Unknown trace opcode " + opcode);
      }
      require(cursor <= events.length, "truncated event " + opcode);
    }
    for (int kind = ACTIVE; kind <= INACTIVE; kind++) {
      if (lists[kind] != null) {
        checksum = mix(checksum, lists[kind].canonicalHash());
      }
      // A source iterator only emits ITERATOR_END when hasNext() observes exhaustion. Allocator
      // code is allowed to abandon an iterator early, so older cursors can legitimately remain on
      // the stack at method completion.
    }
    return checksum;
  }

  private static int listKind(int kind) {
    require(kind == ACTIVE || kind == INACTIVE, "invalid list kind " + kind);
    return kind;
  }

  private static int interval(int interval, int count) {
    require(interval >= 0 && interval < count, "invalid interval " + interval);
    return interval;
  }

  private static int tableId(int table, int count) {
    require(table >= 0 && table < count, "invalid table " + table);
    return table;
  }

  private static int positive(int value, String name) {
    require(value > 0, name + " must be positive");
    return value;
  }

  private static boolean booleanValue(int value) {
    require(value == 0 || value == 1, "invalid boolean " + value);
    return value != 0;
  }

  private static IntList list(IntList[] lists, int kind) {
    IntList list = lists[kind];
    require(list != null, "list has not been created: " + kind);
    return list;
  }

  private static IntCursor iterator(ArrayDeque<IntCursor>[] iterators, int kind) {
    IntCursor iterator = iterators[kind].peek();
    require(iterator != null, "iterator has not been created: " + kind);
    return iterator;
  }

  private static PositionTable table(PositionTable[] tables, int id) {
    PositionTable table = tables[id];
    require(table != null, "table has not been created: " + id);
    return table;
  }

  private static long mix(long hash, long... values) {
    for (long value : values) {
      hash ^= value;
      hash *= 0x100000001b3L;
    }
    return hash;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private static void requireEqual(long expected, long actual, String name) {
    require(expected == actual, name + " differs: expected=" + expected + " actual=" + actual);
  }

  private static void requireEqual(boolean expected, boolean actual, String name) {
    require(expected == actual, name + " differs: expected=" + expected + " actual=" + actual);
  }

  private interface IntList {
    void add(int value);

    boolean remove(int value);

    void clear();

    IntCursor iterator();

    long canonicalHash();
  }

  private interface IntCursor {
    boolean hasNext();

    int next();

    int current();

    void remove();
  }

  private interface ListFactory {
    IntList create();

    static ListFactory create(String backend) {
      switch (backend) {
        case "linked":
          return () -> new BoxedIntList(new LinkedList<>());
        case "array":
          return () -> new BoxedIntList(new ArrayList<>());
        case "dense":
          return DenseIntList::new;
        default:
          throw new IllegalArgumentException("Unknown list backend: " + backend);
      }
    }
  }

  private static final class BoxedIntList implements IntList {
    private final List<Integer> values;

    BoxedIntList(List<Integer> values) {
      this.values = values;
    }

    @Override
    public void add(int value) {
      values.add(value);
    }

    @Override
    public boolean remove(int value) {
      return values.remove(Integer.valueOf(value));
    }

    @Override
    public void clear() {
      values.clear();
    }

    @Override
    public IntCursor iterator() {
      Iterator<Integer> iterator = values.iterator();
      return new IntCursor() {
        private int current = -1;

        @Override
        public boolean hasNext() {
          return iterator.hasNext();
        }

        @Override
        public int next() {
          current = iterator.next();
          return current;
        }

        @Override
        public int current() {
          require(current >= 0, "iterator has no current value");
          return current;
        }

        @Override
        public void remove() {
          iterator.remove();
        }
      };
    }

    @Override
    public long canonicalHash() {
      long hash = 0xcbf29ce484222325L;
      for (int value : values) {
        hash = mix(hash, value);
      }
      return hash;
    }
  }

  private static final class DenseIntList implements IntList {
    private int[] values = new int[16];
    private int size;

    @Override
    public void add(int value) {
      ensureCapacity(size + 1);
      values[size++] = value;
    }

    @Override
    public boolean remove(int value) {
      for (int index = 0; index < size; index++) {
        if (values[index] == value) {
          removeAt(index);
          return true;
        }
      }
      return false;
    }

    @Override
    public void clear() {
      size = 0;
    }

    @Override
    public IntCursor iterator() {
      return new IntCursor() {
        private int next;
        private int current = -1;

        @Override
        public boolean hasNext() {
          return next < size;
        }

        @Override
        public int next() {
          require(hasNext(), "dense iterator exhausted");
          current = next;
          return values[next++];
        }

        @Override
        public int current() {
          require(current >= 0, "dense iterator has no current value");
          return values[current];
        }

        @Override
        public void remove() {
          require(current >= 0, "dense iterator has no removable value");
          removeAt(current);
          next = current;
          current = -1;
        }
      };
    }

    @Override
    public long canonicalHash() {
      long hash = 0xcbf29ce484222325L;
      for (int index = 0; index < size; index++) {
        hash = mix(hash, values[index]);
      }
      return hash;
    }

    private void ensureCapacity(int capacity) {
      if (capacity > values.length) {
        values = Arrays.copyOf(values, Math.max(capacity, values.length * 2));
      }
    }

    private void removeAt(int index) {
      int remaining = size - index - 1;
      if (remaining > 0) {
        System.arraycopy(values, index + 1, values, index, remaining);
      }
      size--;
    }
  }

  private interface PositionTable {
    void set(int index, int value, int typeMask);

    int get(int index);

    void setBlocked(int index);

    boolean isBlocked(int index);

    boolean hasType(int index, int type);

    default void setTemporarilyBlocked(int index) {
      throw new IllegalStateException("not a position wrapper");
    }

    default boolean isTemporarilyBlocked(int index) {
      throw new IllegalStateException("not a position wrapper");
    }
  }

  private interface PositionFactory {
    PositionTable create(int limit);

    PositionTable wrap(PositionTable delegate, int limit);

    static PositionFactory create(String backend) {
      switch (backend) {
        case "legacy":
          return new PositionFactory() {
            @Override
            public PositionTable create(int limit) {
              return new LegacyPositionTable(limit);
            }

            @Override
            public PositionTable wrap(PositionTable delegate, int limit) {
              return new PositionWrapper(delegate, new BitSetTempFlags());
            }
          };
        case "packed":
          return new PositionFactory() {
            @Override
            public PositionTable create(int limit) {
              return new PackedPositionTable(limit, false);
            }

            @Override
            public PositionTable wrap(PositionTable delegate, int limit) {
              return new PositionWrapper(delegate, new PackedTempFlags(limit, false));
            }
          };
        case "eager-packed":
          return new PositionFactory() {
            @Override
            public PositionTable create(int limit) {
              return new PackedPositionTable(limit, true);
            }

            @Override
            public PositionTable wrap(PositionTable delegate, int limit) {
              return new PositionWrapper(delegate, new PackedTempFlags(limit, true));
            }
          };
        default:
          throw new IllegalArgumentException("Unknown position backend: " + backend);
      }
    }
  }

  private static final class LegacyPositionTable implements PositionTable {
    private final int limit;
    private int[] values = initializedValues(16);
    private final BitSet constants = new BitSet();
    private final BitSet monitors = new BitSet();
    private final BitSet newStrings = new BitSet();
    private final BitSet blocked = new BitSet();

    LegacyPositionTable(int limit) {
      this.limit = limit;
    }

    @Override
    public void set(int index, int value, int typeMask) {
      checkIndex(index, limit);
      grow(index + 1);
      values[index] = value;
      constants.set(index, (typeMask & 1) != 0);
      monitors.set(index, (typeMask & 2) != 0);
      newStrings.set(index, (typeMask & 4) != 0);
    }

    @Override
    public int get(int index) {
      return index < values.length ? values[index] : UNSET;
    }

    @Override
    public void setBlocked(int index) {
      blocked.set(index);
    }

    @Override
    public boolean isBlocked(int index) {
      return blocked.get(index);
    }

    @Override
    public boolean hasType(int index, int type) {
      return typeMatches(constants.get(index), monitors.get(index), newStrings.get(index), type);
    }

    private void grow(int minimum) {
      if (minimum <= values.length) {
        return;
      }
      int size = values.length;
      while (size < minimum) {
        size *= 2;
      }
      int oldSize = values.length;
      values = Arrays.copyOf(values, Math.min(size, limit));
      Arrays.fill(values, oldSize, values.length, UNSET);
    }
  }

  private static final class PackedPositionTable implements PositionTable {
    private static final int BLOCKED = 8;

    private final int limit;
    private int[] values;
    private byte[] flags;

    PackedPositionTable(int limit, boolean eager) {
      this.limit = limit;
      int size = eager ? limit : Math.min(16, limit);
      values = initializedValues(size);
      flags = new byte[size];
    }

    @Override
    public void set(int index, int value, int typeMask) {
      checkIndex(index, limit);
      grow(index + 1);
      values[index] = value;
      flags[index] = (byte) ((flags[index] & BLOCKED) | (typeMask & 7));
    }

    @Override
    public int get(int index) {
      return index < values.length ? values[index] : UNSET;
    }

    @Override
    public void setBlocked(int index) {
      grow(index + 1);
      flags[index] |= BLOCKED;
    }

    @Override
    public boolean isBlocked(int index) {
      return index < flags.length && (flags[index] & BLOCKED) != 0;
    }

    @Override
    public boolean hasType(int index, int type) {
      int typeMask = index < flags.length ? flags[index] & 7 : 0;
      return typeMatches(
          (typeMask & 1) != 0, (typeMask & 2) != 0, (typeMask & 4) != 0, type);
    }

    private void grow(int minimum) {
      if (minimum <= values.length) {
        return;
      }
      int size = Math.max(1, values.length);
      while (size < minimum) {
        size *= 2;
      }
      int oldSize = values.length;
      int newSize = size;
      values = Arrays.copyOf(values, newSize);
      flags = Arrays.copyOf(flags, newSize);
      Arrays.fill(values, oldSize, newSize, UNSET);
    }
  }

  private interface TempFlags {
    void set(int index);

    boolean get(int index);
  }

  private static final class BitSetTempFlags implements TempFlags {
    private final BitSet bits = new BitSet();

    @Override
    public void set(int index) {
      bits.set(index);
    }

    @Override
    public boolean get(int index) {
      return bits.get(index);
    }
  }

  private static final class PackedTempFlags implements TempFlags {
    private final int limit;
    private byte[] flags;

    PackedTempFlags(int limit, boolean eager) {
      this.limit = limit;
      flags = new byte[eager ? limit : Math.min(16, limit)];
    }

    @Override
    public void set(int index) {
      grow(index + 1);
      flags[index] = 1;
    }

    @Override
    public boolean get(int index) {
      return index < flags.length && flags[index] != 0;
    }

    private void grow(int minimum) {
      if (minimum <= flags.length) {
        return;
      }
      int size = Math.max(1, flags.length);
      while (size < minimum) {
        size *= 2;
      }
      flags = Arrays.copyOf(flags, size);
    }
  }

  private static final class PositionWrapper implements PositionTable {
    private final PositionTable delegate;
    private final TempFlags temporaryBlocked;

    PositionWrapper(PositionTable delegate, TempFlags temporaryBlocked) {
      this.delegate = delegate;
      this.temporaryBlocked = temporaryBlocked;
    }

    @Override
    public void set(int index, int value, int typeMask) {
      delegate.set(index, value, typeMask);
    }

    @Override
    public int get(int index) {
      return delegate.get(index);
    }

    @Override
    public void setBlocked(int index) {
      delegate.setBlocked(index);
    }

    @Override
    public boolean isBlocked(int index) {
      return delegate.isBlocked(index) || temporaryBlocked.get(index);
    }

    @Override
    public boolean hasType(int index, int type) {
      return delegate.hasType(index, type);
    }

    @Override
    public void setTemporarilyBlocked(int index) {
      temporaryBlocked.set(index);
    }

    @Override
    public boolean isTemporarilyBlocked(int index) {
      return temporaryBlocked.get(index);
    }
  }

  private static boolean typeMatches(
      boolean constant, boolean monitor, boolean newString, int type) {
    switch (type) {
      case 0:
        return monitor;
      case 1:
        return constant;
      case 2:
        return !monitor && !constant && !newString;
      case 3:
        return true;
      default:
        throw new IllegalStateException("Unknown register type " + type);
    }
  }

  private static int[] initializedValues(int size) {
    int[] values = new int[size];
    Arrays.fill(values, UNSET);
    return values;
  }

  private static void checkIndex(int index, int limit) {
    require(index >= 0 && index < limit, "index outside table: " + index + "/" + limit);
  }

  private static final class Trace {
    final String fingerprint;
    final String sha256;
    final MethodTrace[] methods;
    final long eventCount;

    Trace(String fingerprint, String sha256, MethodTrace[] methods, long eventCount) {
      this.fingerprint = fingerprint;
      this.sha256 = sha256;
      this.methods = methods;
      this.eventCount = eventCount;
    }

    static Trace read(Path path) throws IOException {
      String sha256 = sha256(path);
      try (DataInputStream input =
          new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
        requireEqual(MAGIC, input.readInt(), "trace magic");
        requireEqual(TRACE_SCHEMA_VERSION, input.readInt(), "trace schema");
        String fingerprint = input.readUTF();
        require(!fingerprint.isEmpty(), "empty trace fingerprint");
        int methodCount = input.readInt();
        require(methodCount >= 0 && methodCount <= MAX_METHODS, "invalid method count");
        MethodTrace[] methods = new MethodTrace[methodCount];
        long eventCount = 0;
        String previousMethod = null;
        for (int index = 0; index < methodCount; index++) {
          String methodId = input.readUTF();
          require(isSha256(methodId), "invalid method id " + methodId);
          require(previousMethod == null || previousMethod.compareTo(methodId) < 0,
              "method ids are not sorted and unique");
          previousMethod = methodId;
          long assignmentHash = input.readLong();
          int intervalCount = input.readInt();
          int tableCount = input.readInt();
          int eventSize = input.readInt();
          require(intervalCount >= 0, "negative interval count");
          require(tableCount >= 0 && tableCount <= MAX_TABLES_PER_METHOD, "invalid table count");
          require(eventSize >= 0 && eventSize <= MAX_EVENT_INTS, "invalid event count");
          int[] events = new int[eventSize];
          for (int event = 0; event < eventSize; event++) {
            events[event] = input.readInt();
          }
          eventCount += eventSize;
          methods[index] =
              new MethodTrace(methodId, assignmentHash, intervalCount, tableCount, events);
        }
        require(input.read() == -1, "trailing trace data");
        return new Trace(fingerprint, sha256, methods, eventCount);
      }
    }
  }

  private static final class MethodTrace {
    final String methodId;
    final long assignmentHash;
    final int intervalCount;
    final int tableCount;
    final int[] events;

    MethodTrace(
        String methodId,
        long assignmentHash,
        int intervalCount,
        int tableCount,
        int[] events) {
      this.methodId = methodId;
      this.assignmentHash = assignmentHash;
      this.intervalCount = intervalCount;
      this.tableCount = tableCount;
      this.events = events;
    }
  }

  private static final class Metrics {
    final ThreadMXBean threadBean;
    final com.sun.management.ThreadMXBean allocationBean;
    final long threadId;
    final boolean cpuSupported;
    final boolean allocationSupported;

    Metrics(
        ThreadMXBean threadBean,
        com.sun.management.ThreadMXBean allocationBean,
        long threadId,
        boolean cpuSupported,
        boolean allocationSupported) {
      this.threadBean = threadBean;
      this.allocationBean = allocationBean;
      this.threadId = threadId;
      this.cpuSupported = cpuSupported;
      this.allocationSupported = allocationSupported;
    }

    static Metrics create() {
      ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
      boolean cpuSupported = threadBean.isCurrentThreadCpuTimeSupported();
      if (cpuSupported && !threadBean.isThreadCpuTimeEnabled()) {
        threadBean.setThreadCpuTimeEnabled(true);
      }
      com.sun.management.ThreadMXBean allocationBean =
          threadBean instanceof com.sun.management.ThreadMXBean
              ? (com.sun.management.ThreadMXBean) threadBean
              : null;
      boolean allocationSupported =
          allocationBean != null && allocationBean.isThreadAllocatedMemorySupported();
      if (allocationSupported && !allocationBean.isThreadAllocatedMemoryEnabled()) {
        allocationBean.setThreadAllocatedMemoryEnabled(true);
      }
      return new Metrics(
          threadBean,
          allocationBean,
          Thread.currentThread().getId(),
          cpuSupported,
          allocationSupported);
    }

    long cpuNanos() {
      return cpuSupported ? threadBean.getCurrentThreadCpuTime() : -1;
    }

    long allocatedBytes() {
      return allocationSupported ? allocationBean.getThreadAllocatedBytes(threadId) : -1;
    }

    long delta(long before, long after) {
      return before >= 0 && after >= before ? after - before : -1;
    }
  }

  private static final class Options {
    Path trace;
    Path output;
    String expectedFingerprint;
    String listBackend = "linked";
    String positionBackend = "legacy";
    int warmupIterations = 5;
    int measuredIterations = 20;

    static Options parse(String[] arguments) {
      Options options = new Options();
      for (int index = 0; index < arguments.length; index++) {
        String argument = arguments[index];
        switch (argument) {
          case "--trace":
            options.trace = Path.of(value(arguments, ++index, argument));
            break;
          case "--output":
            options.output = Path.of(value(arguments, ++index, argument));
            break;
          case "--expect-fingerprint":
            options.expectedFingerprint = value(arguments, ++index, argument);
            break;
          case "--list":
            options.listBackend = value(arguments, ++index, argument);
            break;
          case "--positions":
            options.positionBackend = value(arguments, ++index, argument);
            break;
          case "--warmup":
            options.warmupIterations = parseCount(value(arguments, ++index, argument), argument, 0);
            break;
          case "--iterations":
            options.measuredIterations =
                parseCount(value(arguments, ++index, argument), argument, 1);
            break;
          default:
            throw new IllegalArgumentException("Unknown argument: " + argument);
        }
      }
      if (options.trace == null) {
        throw new IllegalArgumentException("--trace is required");
      }
      ListFactory.create(options.listBackend);
      PositionFactory.create(options.positionBackend);
      return options;
    }

    private static String value(String[] arguments, int index, String option) {
      if (index >= arguments.length) {
        throw new IllegalArgumentException(option + " requires a value");
      }
      return arguments[index];
    }

    private static int parseCount(String value, String option, int minimum) {
      try {
        int count = Integer.parseInt(value);
        if (count < minimum) {
          throw new IllegalArgumentException(option + " must be >= " + minimum);
        }
        return count;
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException(option + " is not an integer: " + value, exception);
      }
    }
  }

  private static final class Result {
    final Trace trace;
    final Options options;
    final long checksum;
    final long[] wallNanos;
    final long[] cpuNanos;
    final long[] allocatedBytes;
    final boolean cpuSupported;
    final boolean allocationSupported;

    Result(
        Trace trace,
        Options options,
        long checksum,
        long[] wallNanos,
        long[] cpuNanos,
        long[] allocatedBytes,
        boolean cpuSupported,
        boolean allocationSupported) {
      this.trace = trace;
      this.options = options;
      this.checksum = checksum;
      this.wallNanos = wallNanos;
      this.cpuNanos = cpuNanos;
      this.allocatedBytes = allocatedBytes;
      this.cpuSupported = cpuSupported;
      this.allocationSupported = allocationSupported;
    }

    String toJson() {
      StringBuilder json = new StringBuilder();
      json.append("{\n");
      field(json, "schema_version", RESULT_SCHEMA_VERSION, true);
      field(json, "trace_schema_version", TRACE_SCHEMA_VERSION, true);
      field(json, "trace_sha256", trace.sha256, true);
      field(json, "fingerprint", trace.fingerprint, true);
      field(json, "list_backend", options.listBackend, true);
      field(json, "position_backend", options.positionBackend, true);
      field(json, "methods", trace.methods.length, true);
      field(json, "event_ints", trace.eventCount, true);
      field(json, "warmup_iterations", options.warmupIterations, true);
      field(json, "measured_iterations", options.measuredIterations, true);
      field(json, "checksum", String.format(Locale.ROOT, "%016x", checksum), true);
      field(json, "cpu_supported", cpuSupported, true);
      field(json, "allocation_supported", allocationSupported, true);
      array(json, "wall_nanos", wallNanos, true);
      array(json, "cpu_nanos", cpuNanos, true);
      array(json, "allocated_bytes", allocatedBytes, true);
      json.append("  \"summary\": {\n");
      field(json, "wall_median_nanos", median(wallNanos), true, 4);
      field(json, "cpu_median_nanos", medianSupported(cpuNanos), true, 4);
      field(json, "allocated_median_bytes", medianSupported(allocatedBytes), false, 4);
      json.append("  }\n");
      json.append("}\n");
      return json.toString();
    }
  }

  private static long median(long[] values) {
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    int middle = sorted.length / 2;
    if ((sorted.length & 1) == 1) {
      return sorted[middle];
    }
    return sorted[middle - 1] / 2 + sorted[middle] / 2 +
        ((sorted[middle - 1] & 1) + (sorted[middle] & 1)) / 2;
  }

  private static long medianSupported(long[] values) {
    return Arrays.stream(values).anyMatch(value -> value < 0) ? -1 : median(values);
  }

  private static void field(StringBuilder json, String name, String value, boolean comma) {
    json.append("  \"").append(name).append("\": \"").append(escape(value)).append('"');
    json.append(comma ? ",\n" : "\n");
  }

  private static void field(StringBuilder json, String name, long value, boolean comma) {
    field(json, name, value, comma, 2);
  }

  private static void field(
      StringBuilder json, String name, long value, boolean comma, int indentation) {
    indent(json, indentation);
    json.append('"').append(name).append("\": ").append(value);
    json.append(comma ? ",\n" : "\n");
  }

  private static void field(StringBuilder json, String name, boolean value, boolean comma) {
    indent(json, 2);
    json.append('"').append(name).append("\": ").append(value);
    json.append(comma ? ",\n" : "\n");
  }

  private static void array(StringBuilder json, String name, long[] values, boolean comma) {
    indent(json, 2);
    json.append('"').append(name).append("\": [");
    for (int index = 0; index < values.length; index++) {
      if (index > 0) {
        json.append(", ");
      }
      json.append(values[index]);
    }
    json.append(']');
    json.append(comma ? ",\n" : "\n");
  }

  private static void indent(StringBuilder builder, int count) {
    for (int index = 0; index < count; index++) {
      builder.append(' ');
    }
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static void writeAtomically(Path output, String contents) throws IOException {
    Path absolute = output.toAbsolutePath();
    Path parent = absolute.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path temporary = absolute.resolveSibling(absolute.getFileName() + ".partial");
    Files.writeString(temporary, contents, StandardCharsets.UTF_8);
    try {
      Files.move(
          temporary,
          absolute,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException ignored) {
      Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static String sha256(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(path))) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          digest.update(buffer, 0, read);
        }
      }
      StringBuilder value = new StringBuilder(64);
      for (byte item : digest.digest()) {
        value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
      }
      return value.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static boolean isSha256(String value) {
    return value.length() == 64
        && value.chars().allMatch(character -> Character.digit(character, 16) >= 0);
  }

  private static void selfTest() throws Exception {
    Path directory = Files.createTempDirectory("r8-layout-replay-test");
    try {
      Path tracePath = directory.resolve("trace.bin");
      writeSelfTestTrace(tracePath);
      String expectedChecksum = null;
      for (String list : new String[] {"linked", "array", "dense"}) {
        for (String positions : new String[] {"legacy", "packed", "eager-packed"}) {
          Options options = new Options();
          options.trace = tracePath;
          options.listBackend = list;
          options.positionBackend = positions;
          options.warmupIterations = 1;
          options.measuredIterations = 2;
          Result result = benchmark(Trace.read(tracePath), options);
          String checksum = String.format(Locale.ROOT, "%016x", result.checksum);
          if (expectedChecksum == null) {
            expectedChecksum = checksum;
          } else {
            require(expectedChecksum.equals(checksum), "backend checksum differs");
          }
        }
      }
      System.out.println("layout replay self-test passed checksum=" + expectedChecksum);
    } finally {
      try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
        paths.sorted((left, right) -> right.compareTo(left)).forEach(LayoutReplayMain::delete);
      }
    }
  }

  private static void writeSelfTestTrace(Path path) throws IOException {
    int[] events = {
      LIST_NEW, ACTIVE,
      LIST_ADD, ACTIVE, 0,
      LIST_ADD, ACTIVE, 1,
      ITERATOR_BEGIN, ACTIVE,
      ITERATOR_NEXT, ACTIVE, 0,
      ITERATOR_BEGIN, ACTIVE,
      ITERATOR_NEXT, ACTIVE, 0,
      ITERATOR_NEXT, ACTIVE, 1,
      ITERATOR_END, ACTIVE,
      ITERATOR_REMOVE, ACTIVE, 0,
      ITERATOR_NEXT, ACTIVE, 1,
      ITERATOR_END, ACTIVE,
      TABLE_NEW, 0, 32,
      TABLE_SET, 0, 3, 7, 1,
      TABLE_GET, 0, 3, 7,
      TABLE_HAS_TYPE, 0, 3, 1, 1,
      TABLE_IS_BLOCKED, 0, 3, 0,
      TABLE_SET_BLOCKED, 0, 3,
      TABLE_IS_BLOCKED, 0, 3, 1,
      TABLE_IS_BLOCKED, 0, 33, 0,
      TABLE_SET_BLOCKED, 0, 33,
      TABLE_IS_BLOCKED, 0, 33, 1,
      TABLE_NEW_WRAPPER, 1, 0, 32,
      TABLE_SET_TEMP_BLOCKED, 1, 4,
      TABLE_IS_TEMP_BLOCKED, 1, 4, 1,
      TABLE_IS_BLOCKED, 1, 4, 1,
      LIST_CLEAR, ACTIVE
    };
    try (DataOutputStream output =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
      output.writeInt(MAGIC);
      output.writeInt(TRACE_SCHEMA_VERSION);
      output.writeUTF("self-test-fingerprint");
      output.writeInt(1);
      output.writeUTF("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
      output.writeLong(0x123456789abcdef0L);
      output.writeInt(2);
      output.writeInt(2);
      output.writeInt(events.length);
      for (int event : events) {
        output.writeInt(event);
      }
    }
  }

  private static void delete(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      throw new RuntimeException(exception);
    }
  }

  private LayoutReplayMain() {}
}
