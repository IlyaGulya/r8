// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;

public class CommittedSyntheticsCollectionTest {

  @Test
  public void testFactoryIndexedMembershipWithStructuralFallback() {
    DexItemFactory factory = new DexItemFactory();
    DexType methodType = factory.createType("Lsynthetic/Method;");
    DexType classType = factory.createType("Lsynthetic/Class;");

    DexItemFactory equivalentFactory = new DexItemFactory();
    DexType equivalentMethodType = equivalentFactory.createType("Lsynthetic/Method;");
    DexType equivalentClassType = equivalentFactory.createType("Lsynthetic/Class;");

    DexItemFactory collidingFactory = new DexItemFactory();
    DexType collidingMethodType = collidingFactory.createType("Lother/Method;");
    DexType collidingClassType = collidingFactory.createType("Lother/Class;");

    assertEquals(methodType.getFactoryId(), equivalentMethodType.getFactoryId());
    assertEquals(classType.getFactoryId(), equivalentClassType.getFactoryId());
    assertEquals(methodType.getFactoryId(), collidingMethodType.getFactoryId());
    assertEquals(classType.getFactoryId(), collidingClassType.getFactoryId());
    assertNotEquals(methodType.getFactoryIdentity(), equivalentMethodType.getFactoryIdentity());

    CommittedSyntheticsCollection collection =
        new CommittedSyntheticsCollection(
            ImmutableMap.of(methodType, ImmutableList.of()),
            ImmutableMap.of(classType, ImmutableList.of()),
            ImmutableMap.of(),
            ImmutableSet.of());

    assertTrue(collection.containsMethod(methodType));
    assertTrue(collection.containsType(classType));
    assertTrue(collection.containsMethod(equivalentMethodType));
    assertTrue(collection.containsType(equivalentClassType));
    assertFalse(collection.containsMethod(collidingMethodType));
    assertFalse(collection.containsType(collidingClassType));

    CommittedSyntheticsCollection mixedFactoryCollection =
        new CommittedSyntheticsCollection(
            ImmutableMap.of(methodType, ImmutableList.of()),
            ImmutableMap.of(equivalentClassType, ImmutableList.of()),
            ImmutableMap.of(),
            ImmutableSet.of());
    assertTrue(mixedFactoryCollection.containsMethod(equivalentMethodType));
    assertTrue(mixedFactoryCollection.containsType(classType));
    assertFalse(mixedFactoryCollection.containsMethod(collidingMethodType));
    assertFalse(mixedFactoryCollection.containsType(collidingClassType));
  }
}
