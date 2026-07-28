// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex.distribution;

import static com.google.common.base.Predicates.alwaysTrue;
import static com.google.common.base.Predicates.not;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.VirtualFile;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodHandle;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.LensCodeRewriterUtils;
import com.android.tools.r8.utils.ListUtils;
import com.android.tools.r8.utils.Pair;
import com.android.tools.r8.utils.SetUtils;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.timing.Timing;
import com.google.common.base.Predicate;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public class DexDistributionRefinement {

  private final AppView<?> appView;
  private final VirtualFileCycler cycler;
  private final boolean enableContainerDex;
  private final LinkedHashSet<VirtualFile> files;
  private final LensCodeRewriterUtils rewriter;

  // Must be concurrent since we collect concurrently.
  private final Map<String, DexString> shortyCache = new ConcurrentHashMap<>();

  // Mapping from each virtual file to the classes inside the virtual file that are candidates for
  // moving to other virtual files. A LinkedHashSet is used instead of a List to support efficient
  // removal.
  private final Map<VirtualFile, LinkedHashSet<DexProgramClass>>
      fileToClassesWithDeterministicOrder = new IdentityHashMap<>();
  private final Map<DexProgramClass, ClassItems> itemsByClass = new ConcurrentHashMap<>();

  private DexDistributionRefinement(
      AppView<?> appView, VirtualFileCycler cycler, List<VirtualFile> filesSubjectToRefinement) {
    this.appView = appView;
    this.cycler = cycler;
    this.enableContainerDex = appView.options().enableContainerDex();
    this.files = new LinkedHashSet<>(filesSubjectToRefinement);
    this.rewriter = new LensCodeRewriterUtils(appView, true);
    initialize();
  }

  public static void run(
      AppView<?> appView, VirtualFileCycler cycler, ExecutorService executorService, Timing timing)
      throws ExecutionException {
    if (!appView.testing().enableClassToDexDistributionRefinementInDebugMode
        && appView.options().debug) {
      return;
    }
    int numPasses = appView.testing().classToDexDistributionRefinementPasses;
    if (numPasses > 0) {
      runOnPartition(appView, cycler, VirtualFile::isStartup, executorService, timing);
      runOnPartition(appView, cycler, not(VirtualFile::isStartup), executorService, timing);
    }
  }

  private static void runOnPartition(
      AppView<?> appView,
      VirtualFileCycler cycler,
      Predicate<VirtualFile> predicate,
      ExecutorService executorService,
      Timing timing)
      throws ExecutionException {
    List<VirtualFile> filesSubjectToRefinement =
        ListUtils.filter(cycler.getFilesForDistribution(), f -> !f.isEmpty() && predicate.test(f));
    if (filesSubjectToRefinement.size() > 1) {
      timing.begin("Dex distribution refinement");
      new DexDistributionRefinement(appView, cycler, filesSubjectToRefinement)
          .internalRun(executorService, timing);
      timing.end();
    }
  }

  private void initialize() {
    for (VirtualFile file : files) {
      fileToClassesWithDeterministicOrder.put(
          file,
          new LinkedHashSet<>(
              ListUtils.sort(file.classes(), Comparator.comparing(DexClass::getType))));
    }
  }

  private void internalRun(ExecutorService executorService, Timing timing)
      throws ExecutionException {
    // Run refinement.
    boolean hasEmptyFiles = false;
    for (int i = 0; i < appView.testing().classToDexDistributionRefinementPasses; i++) {
      boolean changed = false;
      timing.begin("Pass " + i);
      Iterator<VirtualFile> iterator = files.iterator();
      while (iterator.hasNext()) {
        VirtualFile file = iterator.next();
        if (refineFile(file, executorService, timing)) {
          changed = true;
        }
        // If the file became empty, then don't consider it for any further refinement.
        if (file.getIndexedItems().classes.isEmpty()) {
          iterator.remove();
          hasEmptyFiles = true;
        }
      }
      timing.end();
      if (!changed) {
        break;
      }
    }

    // Fixup empty files.
    if (hasEmptyFiles) {
      cycler.removeEmptyFilesAndRenumber();
    }
  }

  private boolean refineFile(VirtualFile file, ExecutorService executorService, Timing timing)
      throws ExecutionException {
    timing.begin("Refine file " + file.getId());
    boolean changed = false;
    LinkedHashSet<DexProgramClass> classesWithDeterministicOrder =
        fileToClassesWithDeterministicOrder.get(file);

    // Concurrently compute which classes to move where.
    timing.begin("Compute target files");
    Map<DexProgramClass, PendingMoveTask> pendingMoveTasks = new ConcurrentHashMap<>();
    ThreadUtils.processItemsThatMatches(
        classesWithDeterministicOrder,
        alwaysTrue(),
        (clazz, threadTiming) -> {
          ClassItems items = itemsByClass.computeIfAbsent(clazz, this::collectItems);
          PriorityQueue<Pair<VirtualFile, Integer>> targetFiles = findTargetFiles(file, items);
          if (!targetFiles.isEmpty()) {
            pendingMoveTasks.put(clazz, c -> moveClass(c, file, targetFiles, items));
          }
        },
        appView.options(),
        executorService,
        timing,
        timing.beginMerger("Fork", executorService),
        (index, clazz) -> clazz.getTypeName());
    timing.end();

    // Move all classes single threaded.
    timing.begin("Move classes to target files");
    Iterator<DexProgramClass> iterator = classesWithDeterministicOrder.iterator();
    while (iterator.hasNext()) {
      DexProgramClass clazz = iterator.next();
      PendingMoveTask pendingMoveTask = pendingMoveTasks.get(clazz);
      if (pendingMoveTask == null) {
        continue;
      }

      boolean moved = pendingMoveTask.tryMove(clazz);
      if (moved) {
        changed = true;

        // The current virtual file no longer contains the class, so remove it.
        // We concluded that moving the current class C from DEX file X to Y was a good idea.
        // Let's not consider moving class C from Y to another DEX file in the future, which is
        // achieved by not adding the class to the target's set of classes.
        iterator.remove();
      }
    }
    timing.end(); // Move classes to target files
    timing.end(); // Refine file
    return changed;
  }

  private ClassItems collectItems(DexProgramClass clazz) {
    ItemCollector collector = new ItemCollector();
    clazz.collectIndexedItems(appView, collector, rewriter);
    return collector.getItems();
  }

  private PriorityQueue<Pair<VirtualFile, Integer>> findTargetFiles(
      VirtualFile sourceFile, ClassItems items) {
    int estimatedSavingsFromRemovalInBytes =
        getNumberOfItemsWithReferenceCount(items.all, sourceFile, 1);

    // TODO(b/473427453): To improve build speed, consider if we can return null here if if the
    //  estimated savings are small compared to the number of items in the class (i.e., the class
    //  already fits well in the current dex file).

    PriorityQueue<Pair<VirtualFile, Integer>> targetFiles =
        new PriorityQueue<>(Comparator.comparingInt(Pair::getSecond));
    for (VirtualFile targetFile : files) {
      if (targetFile == sourceFile || cannotFit(targetFile, items)) {
        continue;
      }
      int estimatedCostInBytes = getNumberOfItemsWithReferenceCount(items.all, targetFile, 0);
      if (estimatedCostInBytes < estimatedSavingsFromRemovalInBytes) {
        targetFiles.add(new Pair<>(targetFile, estimatedCostInBytes));
      }
    }
    return targetFiles;
  }

  private int getNumberOfItemsWithReferenceCount(
      Set<DexItem> items, VirtualFile targetFile, int theReferenceCount) {
    int result = 0;
    for (DexItem item : items) {
      if (enableContainerDex && item instanceof DexString) {
        // The container has a single DEX file, so don't include the cost/savings from moving
        // strings from one dex file to another.
        continue;
      }
      if (getReferenceCount(item, targetFile) == theReferenceCount) {
        result++;
      }
    }
    return result;
  }

  private int getReferenceCount(DexItem item, VirtualFile file) {
    if (item instanceof DexCallSite) {
      return file.indexedItems.callSites.getInt(item);
    } else if (item instanceof DexField) {
      return file.indexedItems.fields.getInt(item);
    } else if (item instanceof DexMethod) {
      return file.indexedItems.methods.getInt(item);
    } else if (item instanceof DexMethodHandle) {
      return file.indexedItems.methodHandles.getInt(item);
    } else if (item instanceof DexProto) {
      return file.indexedItems.protos.getInt(item);
    } else if (item instanceof DexString) {
      return file.indexedItems.strings.getInt(item);
    } else if (item instanceof DexType) {
      return file.indexedItems.types.getInt(item);
    }
    assert false;
    return 0;
  }

  private boolean cannotFit(VirtualFile file, ClassItems items) {
    int remainingMethods = VirtualFile.MAX_ENTRIES - file.getTransaction().getNumberOfMethods();
    for (int i = 0; i < items.methodsEnd; i++) {
      if (file.indexedItems.methods.getInt(items.capacityItems[i]) == 0 && --remainingMethods < 0) {
        return true;
      }
    }
    int remainingTypes = VirtualFile.MAX_ENTRIES - file.getTransaction().getNumberOfTypes();
    for (int i = items.methodsEnd; i < items.typesEnd; i++) {
      if (file.indexedItems.types.getInt(items.capacityItems[i]) == 0 && --remainingTypes < 0) {
        return true;
      }
    }
    int remainingFields = VirtualFile.MAX_ENTRIES - file.getTransaction().getNumberOfFields();
    for (int i = items.typesEnd; i < items.capacityItems.length; i++) {
      if (file.indexedItems.fields.getInt(items.capacityItems[i]) == 0 && --remainingFields < 0) {
        return true;
      }
    }
    return remainingMethods < 0 || remainingTypes < 0 || remainingFields < 0;
  }

  private boolean moveClass(
      DexProgramClass clazz,
      VirtualFile sourceFile,
      PriorityQueue<Pair<VirtualFile, Integer>> targetFiles,
      ClassItems items) {
    while (!targetFiles.isEmpty()) {
      VirtualFile targetFile = targetFiles.poll().getFirst();
      if (cannotFit(targetFile, items)) {
        continue;
      }
      sourceFile.indexedItems.classes.remove(clazz);
      targetFile.indexedItems.classes.add(clazz);
      for (DexItem item : items.all) {
        adjustReferenceCount(sourceFile, item, -1);
        adjustReferenceCount(targetFile, item, 1);
      }
      return true;
    }
    return false;
  }

  private void adjustReferenceCount(VirtualFile file, DexItem item, int change) {
    if (item instanceof DexCallSite) {
      adjustReferenceCount(file.indexedItems.callSites, (DexCallSite) item, change);
    } else if (item instanceof DexField) {
      adjustReferenceCount(file.indexedItems.fields, (DexField) item, change);
    } else if (item instanceof DexMethod) {
      adjustReferenceCount(file.indexedItems.methods, (DexMethod) item, change);
    } else if (item instanceof DexMethodHandle) {
      adjustReferenceCount(file.indexedItems.methodHandles, (DexMethodHandle) item, change);
    } else if (item instanceof DexProto) {
      adjustReferenceCount(file.indexedItems.protos, (DexProto) item, change);
    } else if (item instanceof DexString) {
      adjustReferenceCount(file.indexedItems.strings, (DexString) item, change);
    } else if (item instanceof DexType) {
      adjustReferenceCount(file.indexedItems.types, (DexType) item, change);
    } else {
      assert false;
    }
  }

  private <T> void adjustReferenceCount(Reference2IntMap<T> items, T item, int change) {
    int count = items.containsKey(item) ? items.getInt(item) : 0;
    if (count == IndexedItemTransaction.NO_REF_COUNT) {
      // Checksum or marker.
      return;
    }
    int newCount = count + change;
    assert newCount >= 0;
    if (newCount == 0) {
      items.removeInt(item);
    } else {
      items.put(item, newCount);
    }
  }

  private class ItemCollector implements IndexedItemCollection {

    private final Set<DexItem> items = SetUtils.newIdentityHashSet();
    private final List<DexField> fields = new ArrayList<>();
    private final List<DexMethod> methods = new ArrayList<>();
    private final List<DexType> types = new ArrayList<>();

    @Override
    public boolean addClass(DexProgramClass clazz) {
      return true;
    }

    @Override
    public boolean addField(DexField field) {
      if (items.add(field)) {
        fields.add(field);
        return true;
      }
      return false;
    }

    @Override
    public boolean addMethod(DexMethod method) {
      if (items.add(method)) {
        methods.add(method);
        return true;
      }
      return false;
    }

    @Override
    public boolean addString(DexString string) {
      return items.add(string);
    }

    @Override
    public boolean addProto(DexProto proto) {
      if (items.add(proto)) {
        DexString shorty =
            shortyCache.computeIfAbsent(
                proto.createShortyString(), appView.dexItemFactory()::createString);
        addString(shorty);
        return true;
      }
      return false;
    }

    @Override
    public boolean addType(DexType type) {
      if (items.add(type)) {
        types.add(type);
        return true;
      }
      return false;
    }

    @Override
    public boolean addCallSite(DexCallSite callSite) {
      return items.add(callSite);
    }

    @Override
    public boolean addMethodHandle(DexMethodHandle methodHandle) {
      return items.add(methodHandle);
    }

    public ClassItems getItems() {
      DexItem[] capacityItems = new DexItem[methods.size() + types.size() + fields.size()];
      int offset = 0;
      for (int i = 0; i < methods.size(); i++) {
        capacityItems[offset++] = methods.get(i);
      }
      int methodsEnd = offset;
      for (int i = 0; i < types.size(); i++) {
        capacityItems[offset++] = types.get(i);
      }
      int typesEnd = offset;
      for (int i = 0; i < fields.size(); i++) {
        capacityItems[offset++] = fields.get(i);
      }
      assert offset == capacityItems.length;
      return new ClassItems(items, capacityItems, methodsEnd, typesEnd);
    }
  }

  private static class ClassItems {

    private final Set<DexItem> all;
    // Methods, types and fields are stored in contiguous ranges to keep capacity checks branch-free
    // without allocating three arrays per class.
    private final DexItem[] capacityItems;
    private final int methodsEnd;
    private final int typesEnd;

    private ClassItems(Set<DexItem> all, DexItem[] capacityItems, int methodsEnd, int typesEnd) {
      this.all = all;
      this.capacityItems = capacityItems;
      this.methodsEnd = methodsEnd;
      this.typesEnd = typesEnd;
    }
  }

  private interface PendingMoveTask {

    boolean tryMove(DexProgramClass clazz);
  }
}
