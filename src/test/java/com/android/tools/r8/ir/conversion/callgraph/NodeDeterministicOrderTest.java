// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion.callgraph;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.graph.ClassAccessFlags;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.FieldCollection.FieldCollectionFactory;
import com.android.tools.r8.graph.GenericSignature.ClassSignature;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.graph.MethodCollection.MethodCollectionFactory;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.origin.SynthesizedOrigin;
import com.android.tools.r8.utils.ReachabilitySensitiveValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class NodeDeterministicOrderTest {

  private static final int HOLDER_COUNT = 8;
  private static final int METHOD_NAME_COUNT = 128;

  @Test
  public void testHolderGroupedOrderMatchesMethodOrder() {
    DexItemFactory factory = new DexItemFactory();
    DexProto[] protos = {
      factory.createProto(factory.voidType),
      factory.createProto(factory.intType),
      factory.createProto(factory.voidType, factory.intType),
      factory.createProto(factory.intType, factory.stringType, factory.intType)
    };
    List<Node> nodes = new ArrayList<>();
    int factoryIdentity = -1;
    int maxFactoryId = -1;
    for (int holderIndex = 0; holderIndex < HOLDER_COUNT; holderIndex++) {
      DexProgramClass holder = createProgramClass(factory, holderIndex);
      if (factoryIdentity < 0) {
        factoryIdentity = holder.type.getFactoryIdentity();
      } else {
        assertTrue(factoryIdentity == holder.type.getFactoryIdentity());
      }
      maxFactoryId = Math.max(maxFactoryId, holder.type.getFactoryId());
      for (int nameIndex = 0; nameIndex < METHOD_NAME_COUNT; nameIndex++) {
        for (DexProto proto : protos) {
          DexMethod reference =
              factory.createMethod(holder.type, proto, String.format("m%03d", nameIndex));
          DexEncodedMethod definition =
              DexEncodedMethod.builder()
                  .setMethod(reference)
                  .setAccessFlags(MethodAccessFlags.fromDexAccessFlags(0))
                  .disableAndroidApiLevelCheck()
                  .build();
          nodes.add(new Node(new ProgramMethod(holder, definition)));
        }
      }
    }
    assertTrue(maxFactoryId >= 0);
    assertTrue(maxFactoryId < nodes.size());

    Collections.shuffle(nodes, new Random(42));
    Node[] expected = nodes.toArray(Node.EMPTY_ARRAY);
    Arrays.sort(expected);
    Node[] actual = Node.prepareForDeterministicTraversal(nodes);
    assertArrayEquals(expected, actual);
  }

  private static DexProgramClass createProgramClass(DexItemFactory factory, int index) {
    return new DexProgramClass(
        factory.createType("LCallGraphOrder" + index + ";"),
        null,
        new SynthesizedOrigin("test", NodeDeterministicOrderTest.class),
        ClassAccessFlags.fromSharedAccessFlags(0),
        factory.objectType,
        DexTypeList.empty(),
        null,
        null,
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        null,
        Collections.emptyList(),
        ClassSignature.noSignature(),
        DexAnnotationSet.empty(),
        FieldCollectionFactory.empty(),
        MethodCollectionFactory.empty(),
        false,
        DexProgramClass::invalidChecksumRequest,
        ReachabilitySensitiveValue.DISABLED);
  }
}
