// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Test;

/** Small Gradle JVM-property fixture, not an R8 compiler or performance test. */
public class ProviderPropertyFixtureTest {
  @Test
  public void propertiesReachTheTestVmAsValues() {
    String supplied = System.getProperty("fixture.provider.path");
    assertTrue("unresolved provider property: " + supplied, new File(supplied).isDirectory());
    assertEquals(System.getProperty("fixture.plain.path"), supplied);
    assertEquals("false", System.getProperty("fixture.provider.boolean"));
  }
}
