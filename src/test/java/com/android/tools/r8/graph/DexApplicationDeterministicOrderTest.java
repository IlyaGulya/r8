// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.graph.FieldCollection.FieldCollectionFactory;
import com.android.tools.r8.graph.GenericSignature.ClassSignature;
import com.android.tools.r8.graph.MethodCollection.MethodCollectionFactory;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.ReachabilitySensitiveValue;
import com.android.tools.r8.utils.timing.Timing;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import org.junit.Test;

public class DexApplicationDeterministicOrderTest extends TestBase {

  @Test
  public void testDeterministicOrderIsCachedPerDirection() {
    InternalOptions options = new InternalOptions();
    DexProgramClass first = createClass(options.itemFactory, "LFirst;");
    DexProgramClass second = createClass(options.itemFactory, "LSecond;");
    DexProgramClass third = createClass(options.itemFactory, "LThird;");
    DirectMappedDexApplication application =
        DirectMappedDexApplication.directBuilder(options, Timing.empty())
            .setFlags(DexApplicationReadFlags.builder().build())
            .replaceClasspathClasses(Collections.emptyList())
            .replaceLibraryClasses(Collections.emptyList())
            .addProgramClasses(Arrays.asList(third, first, second))
            .build();

    Collection<DexProgramClass> forward = application.classesWithDeterministicOrder();
    assertSame(forward, application.classesWithDeterministicOrder());
    assertEquals(Arrays.asList(first, second, third), new ArrayList<>(forward));

    options.testing.reverseClassSortingForDeterminism = true;
    Collection<DexProgramClass> reverse = application.classesWithDeterministicOrder();
    assertNotSame(forward, reverse);
    assertSame(reverse, application.classesWithDeterministicOrder());
    assertEquals(Arrays.asList(third, second, first), new ArrayList<>(reverse));

    options.testing.reverseClassSortingForDeterminism = false;
    assertSame(forward, application.classesWithDeterministicOrder());
  }

  private static DexProgramClass createClass(DexItemFactory factory, String descriptor) {
    return new DexProgramClass(
        factory.createType(descriptor),
        null,
        Origin.unknown(),
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
