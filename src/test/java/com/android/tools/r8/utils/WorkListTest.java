// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.Test;

public class WorkListTest {

  @Test
  public void testNeverUsedWorkListIsEmpty() {
    WorkList<Object> workList = WorkList.newEqualityWorkList();

    assertFalse(workList.hasNext());
    assertTrue(workList.isEmpty());
    assertThrows(NoSuchElementException.class, workList::next);
    assertThrows(NoSuchElementException.class, workList::removeLast);
  }

  @Test
  public void testEqualitySeenSet() {
    WorkList<String> workList = WorkList.newEqualityWorkList();
    String first = new String("item");
    String equal = new String("item");

    assertTrue(workList.addIfNotSeen(first));
    assertFalse(workList.addIfNotSeen(equal));
    assertSame(first, workList.next());
    assertTrue(workList.isEmpty());
  }

  @Test
  public void testIdentitySeenSet() {
    WorkList<String> workList = WorkList.newIdentityWorkList();
    String first = new String("item");
    String equal = new String("item");

    assertTrue(workList.addIfNotSeen(first));
    assertTrue(workList.addIfNotSeen(equal));
    assertSame(first, workList.next());
    assertSame(equal, workList.next());
    assertTrue(workList.isEmpty());
  }

  @Test
  public void testQueueOrder() {
    WorkList<Integer> workList = WorkList.newEqualityWorkList();

    workList.addIgnoringSeenSet(2);
    workList.addFirstIgnoringSeenSet(1);
    workList.addAllIgnoringSeenSet(Arrays.asList(3, 4));

    assertEquals(Integer.valueOf(1), workList.next());
    assertEquals(Integer.valueOf(4), workList.removeLast());
    assertEquals(Integer.valueOf(2), workList.next());
    assertEquals(Integer.valueOf(3), workList.next());
    assertTrue(workList.isEmpty());
  }

  @Test
  public void testExternallyOwnedSeenSet() {
    Set<Integer> seen = new HashSet<>();
    seen.add(1);
    WorkList<Integer> workList = WorkList.newWorkList(seen);

    assertFalse(workList.addIfNotSeen(1));
    assertTrue(workList.addIfNotSeen(2));
    assertSame(seen, workList.getMutableSeenSet());
    assertEquals(Integer.valueOf(2), workList.removeSeen());
    assertFalse(seen.contains(2));
  }
}
