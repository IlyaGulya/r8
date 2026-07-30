// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize;

import static com.android.tools.r8.utils.ConsumerUtils.emptyConsumer;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.ir.analysis.type.TypeAnalysis;
import com.android.tools.r8.ir.code.Assume;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.Value;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class AffectedValues implements Set<Value> {

  private static final AffectedValues EMPTY = new AffectedValues(ImmutableSet.of());

  private Set<Value> affectedValues;

  public AffectedValues() {}

  private AffectedValues(Set<Value> affectedValues) {
    this.affectedValues = affectedValues;
  }

  public static AffectedValues empty() {
    return EMPTY;
  }

  public void narrowingWithAssumeRemoval(AppView<?> appView, IRCode code) {
    narrowingWithAssumeRemoval(appView, code, emptyConsumer());
  }

  public void narrowingWithAssumeRemoval(
      AppView<?> appView, IRCode code, Consumer<TypeAnalysis> typeAnalysisConsumer) {
    if (hasNext()) {
      TypeAnalysis typeAnalysis = new TypeAnalysis(appView, code);
      typeAnalysisConsumer.accept(typeAnalysis);
      typeAnalysis.narrowingWithAssumeRemoval(this);
      clear();
    }
  }

  public void propagate(AppView<?> appView, IRCode code) {
    if (hasNext()) {
      new TypeAnalysis(appView, code).propagate(this);
    }
  }

  public void propagateWithAssumeRemoval(
      AppView<?> appView, IRCode code, Consumer<TypeAnalysis> typeAnalysisConsumer) {
    if (hasNext()) {
      TypeAnalysis typeAnalysis = new TypeAnalysis(appView, code);
      typeAnalysisConsumer.accept(typeAnalysis);
      typeAnalysis.propagateWithAssumeRemoval(this);
      clear();
    }
  }

  public void widening(AppView<?> appView, IRCode code) {
    if (hasNext()) {
      new TypeAnalysis(appView, code).widening(this);
    }
  }

  public void removeAssumeNonNullInstructionsAfterEnumUnboxing() {
    List<Assume> assumeInstructionsToRemove = new ArrayList<>();
    removeIf(
        value -> {
          if (value.isDefinedByInstructionSatisfying(Instruction::isAssume)) {
            Assume assume = value.getDefinition().asAssume();
            if (value.getDefinition().getFirstOperand().getType().isInt()) {
              assumeInstructionsToRemove.add(assume);
              return true;
            }
          }
          return false;
        });
    for (Assume assume : assumeInstructionsToRemove) {
      assume.outValue().replaceUsers(assume.src(), this);
      assume.remove();
    }
  }

  @Override
  public boolean add(Value value) {
    return getOrCreateAffectedValues().add(value);
  }

  @Override
  public boolean addAll(Collection<? extends Value> c) {
    return c.isEmpty() ? false : getOrCreateAffectedValues().addAll(c);
  }

  public void addLiveAffectedValuesOf(Value value, Predicate<BasicBlock> removedBlocks) {
    for (Value affectedValue : value.affectedValues()) {
      if (affectedValue.hasBlock() && !removedBlocks.test(affectedValue.getBlock())) {
        add(affectedValue);
      }
    }
  }

  @Override
  public void clear() {
    if (affectedValues != null) {
      affectedValues.clear();
      affectedValues = null;
    }
  }

  @Override
  public boolean contains(Object o) {
    return affectedValues != null && affectedValues.contains(o);
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    return affectedValues != null ? affectedValues.containsAll(c) : c.isEmpty();
  }

  public boolean hasNext() {
    return !isEmpty();
  }

  @Override
  public boolean isEmpty() {
    return affectedValues == null || affectedValues.isEmpty();
  }

  @Override
  public Iterator<Value> iterator() {
    return affectedValues != null ? affectedValues.iterator() : Collections.emptyIterator();
  }

  @Override
  public boolean remove(Object o) {
    return affectedValues != null && affectedValues.remove(o);
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    return affectedValues != null && affectedValues.removeAll(c);
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    return affectedValues != null && affectedValues.retainAll(c);
  }

  @Override
  public int size() {
    return affectedValues != null ? affectedValues.size() : 0;
  }

  @Override
  public Object[] toArray() {
    return affectedValues != null ? affectedValues.toArray() : new Object[0];
  }

  @Override
  public <T> T[] toArray(T[] a) {
    if (affectedValues != null) {
      return affectedValues.toArray(a);
    }
    if (a.length > 0) {
      a[0] = null;
    }
    return a;
  }

  private Set<Value> getOrCreateAffectedValues() {
    if (affectedValues == null) {
      affectedValues = Sets.newIdentityHashSet();
    }
    return affectedValues;
  }
}
