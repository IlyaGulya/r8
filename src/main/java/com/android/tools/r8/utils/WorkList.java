// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public class WorkList<T> {

  private final Set<T> seen;
  private T firstItem;
  private ArrayDeque<T> additionalItems;

  public static <T> WorkList<T> newEqualityWorkList() {
    return new WorkList<T>(EqualityTest.EQUALS);
  }

  public static <T> WorkList<T> newEqualityWorkList(T item) {
    WorkList<T> workList = new WorkList<>(EqualityTest.EQUALS);
    workList.addIfNotSeen(item);
    return workList;
  }

  public static <T> WorkList<T> newEqualityWorkList(Iterable<T> items) {
    WorkList<T> workList = new WorkList<>(EqualityTest.EQUALS);
    workList.addIfNotSeen(items);
    return workList;
  }

  public static <T> WorkList<T> newIdentityWorkList() {
    return new WorkList<>(EqualityTest.IDENTITY);
  }

  public static <T> WorkList<T> newIdentityWorkList(T item) {
    WorkList<T> workList = new WorkList<>(EqualityTest.IDENTITY);
    workList.addIfNotSeen(item);
    return workList;
  }

  public static <T> WorkList<T> newIdentityWorkList(T item, Set<T> seen) {
    WorkList<T> workList = new WorkList<>(seen);
    workList.addIfNotSeen(item);
    return workList;
  }

  public static <T> WorkList<T> newIdentityWorkList(Iterable<? extends T> items) {
    WorkList<T> workList = new WorkList<>(EqualityTest.IDENTITY);
    workList.addIfNotSeen(items);
    return workList;
  }

  public static <T> WorkList<T> newWorkList(Set<T> seen) {
    return new WorkList<>(seen);
  }

  private WorkList(EqualityTest equalityTest) {
    this(equalityTest == EqualityTest.EQUALS ? new HashSet<>(3) : SetUtils.newIdentityHashSet(3));
  }

  private WorkList(Set<T> seen) {
    this.seen = seen;
  }

  private ArrayDeque<T> getOrCreateAdditionalItems() {
    if (additionalItems == null) {
      additionalItems = new ArrayDeque<>();
    }
    return additionalItems;
  }

  private void addLast(T item) {
    Objects.requireNonNull(item);
    if (firstItem == null) {
      firstItem = item;
    } else {
      getOrCreateAdditionalItems().addLast(item);
    }
  }

  public void addIgnoringSeenSet(T item) {
    addLast(item);
  }

  public void addAllIgnoringSeenSet(Iterable<T> items) {
    items.forEach(this::addLast);
  }

  public void addIfNotSeen(Iterable<? extends T> items) {
    items.forEach(this::addIfNotSeen);
  }

  public void addIfNotSeen(T[] items) {
    for (T item : items) {
      addIfNotSeen(item);
    }
  }

  public boolean addIfNotSeen(T item) {
    if (seen.add(item)) {
      addLast(item);
      return true;
    }
    return false;
  }

  public boolean addFirstIfNotSeen(T item) {
    if (seen.add(item)) {
      addFirstIgnoringSeenSet(item);
      return true;
    }
    return false;
  }

  public WorkList<T> process(Consumer<T> consumer) {
    return process((item, ignored) -> consumer.accept(item));
  }

  public WorkList<T> process(BiConsumer<T, WorkList<T>> consumer) {
    while (hasNext()) {
      consumer.accept(next(), this);
    }
    return this;
  }

  public <TB, TC> TraversalContinuation<TB, TC> run(Function<T, TraversalContinuation<TB, TC>> fn) {
    return run((item, ignored) -> fn.apply(item));
  }

  public <TB, TC> TraversalContinuation<TB, TC> run(
      BiFunction<T, WorkList<T>, TraversalContinuation<TB, TC>> fn) {
    while (hasNext()) {
      TraversalContinuation<TB, TC> result = fn.apply(next(), this);
      if (result.shouldBreak()) {
        return result;
      }
    }
    return TraversalContinuation.doContinue();
  }

  public void addFirstIgnoringSeenSet(T item) {
    Objects.requireNonNull(item);
    if (firstItem != null) {
      getOrCreateAdditionalItems().addFirst(firstItem);
    }
    firstItem = item;
  }

  public boolean hasNext() {
    return firstItem != null;
  }

  public boolean isEmpty() {
    return !hasNext();
  }

  public boolean isSeen(T item) {
    return seen.contains(item);
  }

  public boolean markAsSeen(T item) {
    return seen.add(item);
  }

  public void markAsSeen(Iterable<T> items) {
    items.forEach(this::markAsSeen);
  }

  public T next() {
    T result = firstItem;
    if (result == null) {
      throw new NoSuchElementException();
    }
    firstItem = additionalItems == null ? null : additionalItems.pollFirst();
    return result;
  }

  public T removeLast() {
    T result = additionalItems == null ? null : additionalItems.pollLast();
    if (result != null) {
      return result;
    }
    result = firstItem;
    if (result == null) {
      throw new NoSuchElementException();
    }
    firstItem = null;
    return result;
  }

  public T removeSeen() {
    T next = next();
    seen.remove(next);
    return next;
  }

  public void removeSeen(T element) {
    boolean removed = seen.remove(element);
    assert removed;
  }

  public void clearSeen() {
    seen.clear();
  }

  public Set<T> getSeenSet() {
    return SetUtils.unmodifiableForTesting(seen);
  }

  public Set<T> getMutableSeenSet() {
    return seen;
  }

  public enum EqualityTest {
    EQUALS,
    IDENTITY
  }
}
