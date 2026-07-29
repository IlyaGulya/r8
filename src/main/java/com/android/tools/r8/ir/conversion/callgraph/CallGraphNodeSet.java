// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion.callgraph;

import com.google.common.collect.Sets;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

class CallGraphNodeSet extends AbstractSet<Node> {

  private static final int BITMAP_REMOVAL_THRESHOLD = 64;

  // Non-null during concurrent graph construction once the first edge is added.
  private Set<Node> mutable;

  // Non-null after freeze(). The elements are ordered by the deterministic call-graph rank.
  private Node[] elements;

  // Lazily allocated for removals from large frozen sets. Small sets preserve ordering by shifting.
  private long[] removed;
  private int size;

  @Override
  public boolean add(Node node) {
    if (elements != null) {
      throw new UnsupportedOperationException("Call graph edges are frozen");
    }
    if (mutable == null) {
      mutable = Sets.newIdentityHashSet();
    }
    return mutable.add(node);
  }

  @Override
  public boolean contains(Object object) {
    if (!(object instanceof Node)) {
      return false;
    }
    Node node = (Node) object;
    if (elements == null) {
      return mutable != null && mutable.contains(node);
    }
    int index = binarySearch(node);
    return index >= 0 && elements[index] == node && !isRemoved(index);
  }

  void freeze() {
    assert elements == null;
    if (mutable == null) {
      elements = Node.EMPTY_ARRAY;
      return;
    }
    elements = mutable.toArray(Node.EMPTY_ARRAY);
    mutable = null;
    Arrays.sort(
        elements,
        (first, second) ->
            Integer.compare(first.getCallGraphOrder(), second.getCallGraphOrder()));
    size = elements.length;
  }

  Set<Node> getWithDeterministicOrder() {
    if (elements != null) {
      return this;
    }
    if (mutable == null || mutable.isEmpty()) {
      return Collections.emptySet();
    }
    if (mutable.size() == 1) {
      return mutable;
    }
    return new TreeSet<>(mutable);
  }

  @Override
  public boolean isEmpty() {
    return size() == 0;
  }

  @Override
  public Iterator<Node> iterator() {
    if (elements == null) {
      return mutable != null ? mutable.iterator() : Collections.emptyIterator();
    }
    return new Iterator<Node>() {

      private int nextIndex = findNextIndex(0);

      @Override
      public boolean hasNext() {
        return nextIndex < (removed == null ? size : elements.length);
      }

      @Override
      public Node next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        Node result = elements[nextIndex];
        nextIndex = findNextIndex(nextIndex + 1);
        return result;
      }
    };
  }

  @Override
  public boolean remove(Object object) {
    if (!(object instanceof Node)) {
      return false;
    }
    Node node = (Node) object;
    if (elements == null) {
      return mutable != null && mutable.remove(node);
    }
    int index = binarySearch(node);
    if (index < 0 || elements[index] != node || isRemoved(index)) {
      return false;
    }
    if (elements.length < BITMAP_REMOVAL_THRESHOLD) {
      int moved = size - index - 1;
      if (moved > 0) {
        System.arraycopy(elements, index + 1, elements, index, moved);
      }
      elements[--size] = null;
    } else {
      if (removed == null) {
        removed = new long[(elements.length + Long.SIZE - 1) / Long.SIZE];
      }
      removed[index / Long.SIZE] |= 1L << index;
      size--;
    }
    return true;
  }

  @Override
  public int size() {
    return elements != null ? size : mutable != null ? mutable.size() : 0;
  }

  private int binarySearch(Node node) {
    int key = node.getCallGraphOrder();
    if (key < 0) {
      return -1;
    }
    int low = 0;
    int high = (removed == null ? size : elements.length) - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      int comparison = Integer.compare(elements[middle].getCallGraphOrder(), key);
      if (comparison < 0) {
        low = middle + 1;
      } else if (comparison > 0) {
        high = middle - 1;
      } else {
        return middle;
      }
    }
    return -1;
  }

  private int findNextIndex(int index) {
    if (removed == null) {
      return Math.min(index, size);
    }
    while (index < elements.length && isRemoved(index)) {
      index++;
    }
    return index;
  }

  private boolean isRemoved(int index) {
    return removed != null && (removed[index / Long.SIZE] & (1L << index)) != 0;
  }
}
