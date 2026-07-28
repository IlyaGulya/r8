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
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
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
  // The cost model only needs to know if an item is present in each file. For small partitions,
  // represent this as a bit mask indexed by a dense, refinement-local item ID. This replaces a
  // reference-map probe for every item/target-file pair with sequential int-array reads.
  private final Map<VirtualFile, Integer> fileIndices;
  private final Reference2IntOpenHashMap<DexItem> itemIds;
  private final int[] itemFileMemberships;

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
    // Keep the original reference-count lookup for partitions that do not fit in a positive int
    // bit mask.
    if (files.size() <= Integer.SIZE - 1) {
      fileIndices = new IdentityHashMap<>(files.size());
      ItemFileMembership itemFileMembership = createItemFileMembership();
      itemIds = itemFileMembership.itemIds;
      itemFileMemberships = itemFileMembership.memberships;
    } else {
      fileIndices = null;
      itemIds = null;
      itemFileMemberships = null;
    }
  }

  private ItemFileMembership createItemFileMembership() {
    int itemFileEdges = 0;
    for (VirtualFile file : files) {
      itemFileEdges += file.indexedItems.callSites.size();
      itemFileEdges += file.indexedItems.fields.size();
      itemFileEdges += file.indexedItems.methods.size();
      itemFileEdges += file.indexedItems.methodHandles.size();
      itemFileEdges += file.indexedItems.protos.size();
      itemFileEdges += file.indexedItems.strings.size();
      itemFileEdges += file.indexedItems.types.size();
    }
    Reference2IntOpenHashMap<DexItem> itemIds = new Reference2IntOpenHashMap<>(itemFileEdges);
    itemIds.defaultReturnValue(-1);
    int[] memberships = new int[itemFileEdges];
    int fileIndex = 0;
    for (VirtualFile file : files) {
      fileIndices.put(file, fileIndex);
      int fileBit = 1 << fileIndex;
      addFileMembership(itemIds, memberships, file.indexedItems.callSites, fileBit);
      addFileMembership(itemIds, memberships, file.indexedItems.fields, fileBit);
      addFileMembership(itemIds, memberships, file.indexedItems.methods, fileBit);
      addFileMembership(itemIds, memberships, file.indexedItems.methodHandles, fileBit);
      addFileMembership(itemIds, memberships, file.indexedItems.protos, fileBit);
      addFileMembership(itemIds, memberships, file.indexedItems.strings, fileBit);
      addFileMembership(itemIds, memberships, file.indexedItems.types, fileBit);
      fileIndex++;
    }
    return new ItemFileMembership(itemIds, Arrays.copyOf(memberships, itemIds.size()));
  }

  private static <T extends DexItem> void addFileMembership(
      Reference2IntOpenHashMap<DexItem> itemIds,
      int[] memberships,
      Reference2IntMap<T> items,
      int fileBit) {
    for (Reference2IntMap.Entry<T> entry : items.reference2IntEntrySet()) {
      if (entry.getIntValue() != 0) {
        DexItem item = entry.getKey();
        int itemId = itemIds.getInt(item);
        if (itemId < 0) {
          itemId = itemIds.size();
          itemIds.put(item, itemId);
        }
        memberships[itemId] |= fileBit;
      }
    }
  }

  private static class ItemFileMembership {

    private final Reference2IntOpenHashMap<DexItem> itemIds;
    private final int[] memberships;

    private ItemFileMembership(Reference2IntOpenHashMap<DexItem> itemIds, int[] memberships) {
      this.itemIds = itemIds;
      this.memberships = memberships;
    }
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
    int[] presentItemsByFile =
        itemFileMemberships != null ? computePresentItemsByFile(items) : null;
    int numberOfItems = presentItemsByFile != null ? items.membershipItemIds.length : 0;

    // TODO(b/473427453): To improve build speed, consider if we can return null here if if the
    //  estimated savings are small compared to the number of items in the class (i.e., the class
    //  already fits well in the current dex file).

    PriorityQueue<Pair<VirtualFile, Integer>> targetFiles =
        new PriorityQueue<>(Comparator.comparingInt(Pair::getSecond));
    for (VirtualFile targetFile : files) {
      if (targetFile == sourceFile || cannotFit(targetFile, items)) {
        continue;
      }
      int estimatedCostInBytes =
          presentItemsByFile != null
              ? numberOfItems - presentItemsByFile[fileIndices.get(targetFile)]
              : getNumberOfItemsWithReferenceCount(items.all, targetFile, 0);
      if (estimatedCostInBytes < estimatedSavingsFromRemovalInBytes) {
        targetFiles.add(new Pair<>(targetFile, estimatedCostInBytes));
      }
    }
    return targetFiles;
  }

  private int[] computePresentItemsByFile(ClassItems items) {
    int[] presentItemsByFile = new int[fileIndices.size()];
    for (int itemId : items.membershipItemIds) {
      int membership = itemFileMemberships[itemId];
      assert membership != 0;
      // Iterate only the files containing the item instead of probing every target file.
      while (membership != 0) {
        int fileIndex = Integer.numberOfTrailingZeros(membership);
        presentItemsByFile[fileIndex]++;
        membership &= membership - 1;
      }
    }
    return presentItemsByFile;
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
      int sourceFileBit = getFileBit(sourceFile);
      int targetFileBit = getFileBit(targetFile);
      for (DexItem item : items.all) {
        adjustReferenceCount(sourceFile, sourceFileBit, item, -1);
        adjustReferenceCount(targetFile, targetFileBit, item, 1);
      }
      return true;
    }
    return false;
  }

  private int getFileBit(VirtualFile file) {
    return itemFileMemberships != null ? 1 << fileIndices.get(file) : 0;
  }

  private void adjustReferenceCount(VirtualFile file, int fileBit, DexItem item, int change) {
    int newCount;
    if (item instanceof DexCallSite) {
      newCount = adjustReferenceCount(file.indexedItems.callSites, (DexCallSite) item, change);
    } else if (item instanceof DexField) {
      newCount = adjustReferenceCount(file.indexedItems.fields, (DexField) item, change);
    } else if (item instanceof DexMethod) {
      newCount = adjustReferenceCount(file.indexedItems.methods, (DexMethod) item, change);
    } else if (item instanceof DexMethodHandle) {
      newCount =
          adjustReferenceCount(file.indexedItems.methodHandles, (DexMethodHandle) item, change);
    } else if (item instanceof DexProto) {
      newCount = adjustReferenceCount(file.indexedItems.protos, (DexProto) item, change);
    } else if (item instanceof DexString) {
      newCount = adjustReferenceCount(file.indexedItems.strings, (DexString) item, change);
    } else if (item instanceof DexType) {
      newCount = adjustReferenceCount(file.indexedItems.types, (DexType) item, change);
    } else {
      assert false;
      return;
    }
    if (itemFileMemberships != null && newCount != IndexedItemTransaction.NO_REF_COUNT) {
      int itemId = itemIds.getInt(item);
      assert itemId >= 0;
      int membership = itemFileMemberships[itemId];
      // Moves are applied single threaded after all concurrent cost computations have completed.
      itemFileMemberships[itemId] = newCount == 0 ? membership & ~fileBit : membership | fileBit;
    }
  }

  private <T> int adjustReferenceCount(Reference2IntMap<T> items, T item, int change) {
    int count = items.containsKey(item) ? items.getInt(item) : 0;
    if (count == IndexedItemTransaction.NO_REF_COUNT) {
      // Checksum or marker.
      return count;
    }
    int newCount = count + change;
    assert newCount >= 0;
    if (newCount == 0) {
      items.removeInt(item);
    } else {
      items.put(item, newCount);
    }
    return newCount;
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
      int[] membershipItemIds = null;
      if (itemIds != null) {
        membershipItemIds = new int[items.size()];
        offset = 0;
        for (DexItem item : items) {
          if (enableContainerDex && item instanceof DexString) {
            continue;
          }
          int itemId = itemIds.getInt(item);
          assert itemId >= 0;
          membershipItemIds[offset++] = itemId;
        }
        if (offset < membershipItemIds.length) {
          membershipItemIds = Arrays.copyOf(membershipItemIds, offset);
        }
      }
      return new ClassItems(items, capacityItems, methodsEnd, typesEnd, membershipItemIds);
    }
  }

  private static class ClassItems {

    private final Set<DexItem> all;
    // Methods, types and fields are stored in contiguous ranges to keep capacity checks branch-free
    // without allocating three arrays per class.
    private final DexItem[] capacityItems;
    private final int methodsEnd;
    private final int typesEnd;
    private final int[] membershipItemIds;

    private ClassItems(
        Set<DexItem> all,
        DexItem[] capacityItems,
        int methodsEnd,
        int typesEnd,
        int[] membershipItemIds) {
      this.all = all;
      this.capacityItems = capacityItems;
      this.methodsEnd = methodsEnd;
      this.typesEnd = typesEnd;
      this.membershipItemIds = membershipItemIds;
    }
  }

  private interface PendingMoveTask {

    boolean tryMove(DexProgramClass clazz);
  }
}
