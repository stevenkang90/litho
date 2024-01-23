/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho

import android.graphics.Rect
import androidx.collection.LongSparseArray
import androidx.core.view.ViewCompat
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.rendercore.LayoutCache
import com.facebook.rendercore.LayoutResult
import com.facebook.rendercore.MountState
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.incrementalmount.ExcludeFromIncrementalMountBinder
import com.facebook.rendercore.incrementalmount.IncrementalMountOutput
import com.facebook.rendercore.incrementalmount.IncrementalMountRenderCoreExtension
import com.facebook.rendercore.visibility.VisibilityOutput
import kotlin.math.max
import kotlin.math.min

object LithoReducer {

  private const val DUPLICATE_TRANSITION_IDS = "LayoutState:DuplicateTransitionIds"

  // region public methods
  @JvmStatic
  fun reduce(
      lsc: LithoLayoutContext,
      resolveResult: ResolveResult,
      treeId: Int,
      layoutCache: LayoutCache,
      reductionState: ReductionState,
  ): LayoutState {
    setSizeAfterMeasureAndCollectResults(reductionState.c, lsc, reductionState)
    return reductionState.createLayoutStateFromReductionState(
        lsc, resolveResult, treeId, layoutCache)
  }

  @JvmStatic
  fun addRootHostRenderTreeNode(
      layoutState: LayoutState,
      result: LayoutResult? = null,
      hierarchy: DebugHierarchy.Node? = null,
  ) {
    val reductionState = createReductionStateFromLayoutState(layoutState)
    addRootHostRenderTreeNode(reductionState, result, hierarchy)
    reductionState.mergeReductionStateIntoLayoutState(layoutState)
  }

  private fun createReductionStateFromLayoutState(layoutState: LayoutState): ReductionState {
    if (layoutState?.resolveResult == null) {
      throw IllegalArgumentException("Both layoutState and resolveResult cannot be null here.")
    }
    return ReductionState(
        c = layoutState.mResolveResult.context,
        sizeConstraints = layoutState.sizeConstraints,
        currentLayoutState = layoutState,
        root = layoutState.mLayoutResult,
        offsetRootX = layoutState.mRootX,
        offsetRootY = layoutState.mRootY)
  }

  private fun setSizeAfterMeasureAndCollectResults(
      c: ComponentContext,
      lithoLayoutContext: LithoLayoutContext,
      reductionState: ReductionState,
  ) {
    if (lithoLayoutContext.isFutureReleased) {
      return
    }
    check(reductionState.mountableOutputs.isEmpty()) {
      """Attempting to collect results on an already populated ReductionState.
        | Root: ${reductionState.componentRootName}"""
          .trimIndent()
    }

    val isTracing: Boolean = ComponentsSystrace.isTracing
    val widthSpec: Int = reductionState.widthSpec
    val heightSpec: Int = reductionState.heightSpec
    val root: LayoutResult? = reductionState.layoutResult
    val rootWidth: Int = root?.width ?: 0
    val rootHeight: Int = root?.height ?: 0

    reductionState.width =
        when (SizeSpec.getMode(widthSpec)) {
          SizeSpec.EXACTLY -> SizeSpec.getSize(widthSpec)
          SizeSpec.AT_MOST -> max(0, min(rootWidth, SizeSpec.getSize(widthSpec)))
          SizeSpec.UNSPECIFIED -> rootWidth
          else -> reductionState.width
        }
    reductionState.height =
        when (SizeSpec.getMode(heightSpec)) {
          SizeSpec.EXACTLY -> SizeSpec.getSize(heightSpec)
          SizeSpec.AT_MOST -> max(0, min(rootHeight, SizeSpec.getSize(heightSpec)))
          SizeSpec.UNSPECIFIED -> rootHeight
          else -> reductionState.height
        }

    if (root == null) {
      return
    }

    var parent: RenderTreeNode? = null
    var hierarchy: DebugHierarchy.Node? = null
    if (c.mLithoConfiguration.componentsConfig.shouldAddHostViewForRootComponent) {
      hierarchy = if (root is LithoLayoutResult) root.node.getDebugHierarchy() else null
      addRootHostRenderTreeNode(reductionState, root, hierarchy)
      parent = reductionState.mountableOutputs[0]
    }

    if (isTracing) {
      ComponentsSystrace.beginSection("collectResults")
    }
    collectResults(
        parentContext = c,
        result = root,
        reductionState = reductionState,
        lithoLayoutContext = lithoLayoutContext,
        x = 0,
        y = 0,
        parent = parent,
        parentHierarchy = hierarchy)
    if (isTracing) {
      ComponentsSystrace.endSection()
    }

    if (isTracing) {
      ComponentsSystrace.beginSection("sortMountableOutputs")
    }

    sortTops(reductionState)
    sortBottoms(reductionState)

    if (isTracing) {
      ComponentsSystrace.endSection()
    }

    val nodeForSaving: LithoNode? = reductionState.rootNode
    val layoutResultForSaving: LayoutResult? = reductionState.layoutResult

    // clean it up for sanity
    reductionState.rootNode = null
    reductionState.layoutResult = null

    // enabled for debugging and end to end tests
    if (ComponentsConfiguration.isDebugModeEnabled || ComponentsConfiguration.isEndToEndTestRun) {
      reductionState.rootNode = nodeForSaving
      reductionState.layoutResult = layoutResultForSaving
      return
    }

    // override used by analytics teams
    if (ComponentsConfiguration.keepLayoutResults) {
      reductionState.layoutResult = layoutResultForSaving
    }
  }

  private fun addRootHostRenderTreeNode(
      reductionState: ReductionState,
      result: LayoutResult? = null,
      hierarchy: DebugHierarchy.Node? = null,
  ) {
    val width: Int = result?.width ?: 0
    val height: Int = result?.height ?: 0
    val debugNode: DebugHierarchy.Node? = hierarchy?.mutateType(OutputUnitType.HOST)
    val rootHostRenderUnit = createRootHostRenderUnit(reductionState.componentContext)

    val node: RenderTreeNode =
        create(
            unit = rootHostRenderUnit,
            bounds = Rect(0, 0, width, height),
            layoutData =
                LithoLayoutData(
                    width = width,
                    height = height,
                    currentLayoutStateId = reductionState.id,
                    previousLayoutStateId = reductionState.previousLayoutStateId,
                    expandedTouchBounds = null,
                    layoutData = null,
                    isSizeDependant = true,
                    debugHierarchy = debugNode))

    addRenderTreeNode(
        reductionState = reductionState,
        node = node,
        result = result,
        unit = rootHostRenderUnit,
        type = OutputUnitType.HOST)
  }

  private fun createRootHostRenderUnit(context: ComponentContext) =
      MountSpecLithoRenderUnit.create(
          id = MountState.ROOT_HOST_ID,
          component = HostComponent.create(context),
          commonDynamicProps = null,
          context = null,
          nodeInfo = null,
          flags = 0,
          importantForAccessibility = ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
          updateState = MountSpecLithoRenderUnit.STATE_DIRTY,
          debugKey = LithoNodeUtils.getDebugKey("root-host", OutputUnitType.HOST))

  private fun createAnimatableItem(
      unit: LithoRenderUnit,
      absoluteBounds: Rect,
      @OutputUnitType outputType: Int,
      transitionId: TransitionId? = null,
  ): AnimatableItem =
      LithoAnimtableItem(unit.id, absoluteBounds, outputType, unit.nodeInfo, transitionId)

  private fun createDiffNode(tail: ScopedComponentInfo, parent: DiffNode? = null): DiffNode {
    val diffNode = DefaultDiffNode(tail.component, tail.context.globalKey, tail)
    parent?.addChild(diffNode)
    return diffNode
  }
  // endregion

  /**
   * Acquires a new layout output for the internal node and its associated component. It returns
   * null if there's no component associated with the node as the mount pass only cares about nodes
   * that will potentially mount content into the component host.
   */
  private fun createContentRenderTreeNode(
      result: LayoutResult,
      node: LithoNode,
      bounds: Rect,
      reductionState: ReductionState,
      expandedTouchBounds: Rect?,
      parent: RenderTreeNode? = null,
      debugHierarchyNode: DebugHierarchy.Node? = null,
  ): RenderTreeNode? {

    if (Component.isLayoutSpec(node.tailComponent) ||
        (result !is LithoLayoutResult) ||
        result.measureHadExceptions) {
      // back out when dealing with Layout Specs or if there was an error during measure
      return null
    }

    val unit: LithoRenderUnit = result.contentRenderUnit ?: return null
    val layoutData: Any? = result.layoutData
    val debugNode: DebugHierarchy.Node? = debugHierarchyNode?.mutateType(OutputUnitType.CONTENT)
    return createRenderTreeNode(
        unit = unit,
        bounds =
            Rect(
                bounds.left + result.adjustedLeft(),
                bounds.top + result.adjustedTop(),
                bounds.right + result.adjustedRight(),
                bounds.bottom + result.adjustedBottom()),
        reductionState = reductionState,
        isSizeDependant =
            if (node.tailComponent is SpecGeneratedComponent) {
              (node.tailComponent as SpecGeneratedComponent).isMountSizeDependent
            } else {
              false
            },
        expandedTouchBounds = expandedTouchBounds,
        layoutData = layoutData,
        parent = parent,
        debugHierarchyNode = debugNode)
  }

  private fun createHostRenderTreeNode(
      unit: LithoRenderUnit,
      bounds: Rect,
      reductionState: ReductionState,
      expandedTouchBounds: Rect?,
      parent: RenderTreeNode? = null,
      hierarchy: DebugHierarchy.Node? = null,
  ): RenderTreeNode =
      createRenderTreeNode(
          unit = unit,
          bounds = bounds,
          reductionState = reductionState,
          isSizeDependant = true,
          parent = parent,
          expandedTouchBounds = expandedTouchBounds,
          debugHierarchyNode = hierarchy?.mutateType(OutputUnitType.HOST))

  private fun createRenderTreeNode(
      unit: LithoRenderUnit,
      bounds: Rect,
      reductionState: ReductionState,
      isSizeDependant: Boolean,
      expandedTouchBounds: Rect?,
      layoutData: Any? = null,
      parent: RenderTreeNode? = null,
      debugHierarchyNode: DebugHierarchy.Node? = null,
  ): RenderTreeNode {

    val hostTranslationX: Int = parent?.absoluteX ?: 0
    val hostTranslationY: Int = parent?.absoluteY ?: 0
    val l: Int = bounds.left - hostTranslationX
    val t: Int = bounds.top - hostTranslationY
    val r: Int = l + bounds.width()
    val b: Int = t + bounds.height()
    val resolvedBounds = Rect(l, t, r, b)

    return create(
        unit = unit,
        bounds = resolvedBounds,
        layoutData =
            LithoLayoutData(
                width = resolvedBounds.width(),
                height = resolvedBounds.height(),
                currentLayoutStateId = reductionState.id,
                previousLayoutStateId = reductionState.previousLayoutStateId,
                expandedTouchBounds = expandedTouchBounds,
                isSizeDependant = isSizeDependant,
                layoutData = layoutData,
                debugHierarchy = debugHierarchyNode),
        parent = parent)
  }

  /**
   * Acquires a [VisibilityOutput] object and computes the bounds for it using the information
   * stored in the [LithoNode].
   */
  private fun createVisibilityOutput(
      node: LithoNode,
      bounds: Rect,
      renderTreeNode: RenderTreeNode?
  ): VisibilityOutput {

    val visibleHandler: EventHandler<VisibleEvent>? = node.visibleHandler
    val focusedHandler: EventHandler<FocusedVisibleEvent>? = node.focusedHandler
    val unfocusedHandler: EventHandler<UnfocusedVisibleEvent>? = node.unfocusedHandler
    val fullImpressionHandler: EventHandler<FullImpressionVisibleEvent>? =
        node.fullImpressionHandler
    val invisibleHandler: EventHandler<InvisibleEvent>? = node.invisibleHandler
    val visibleRectChangedEventHandler: EventHandler<VisibilityChangedEvent>? =
        node.visibilityChangedHandler
    val component: Component = node.tailComponent
    val componentGlobalKey: String = node.tailComponentKey

    return VisibilityOutput(
        componentGlobalKey,
        component.simpleName,
        Rect(bounds),
        renderTreeNode != null,
        renderTreeNode?.renderUnit?.id ?: 0,
        node.visibleHeightRatio,
        node.visibleWidthRatio,
        node.visibilityOutputTag,
        visibleHandler,
        invisibleHandler,
        focusedHandler,
        unfocusedHandler,
        fullImpressionHandler,
        visibleRectChangedEventHandler)
  }

  private fun createTestOutput(
      node: LithoNode,
      bounds: Rect,
      renderUnit: LithoRenderUnit? = null
  ): TestOutput {
    val output = TestOutput()
    output.testKey = checkNotNull(node.testKey)
    output.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    if (renderUnit != null) {
      output.layoutOutputId = renderUnit.id
    }
    return output
  }

  /**
   * Collects layout outputs and release the layout tree. The layout outputs hold necessary
   * information to be used by [MountState] to mount components into a [ComponentHost].
   *
   * Whenever a component has view content (view tags, click handler, etc), a new host 'marker' is
   * added for it. The mount pass will use the markers to decide which host should be used for each
   * layout output. The root node unconditionally generates a layout output corresponding to the
   * root host.
   *
   * The order of layout outputs follows a depth-first traversal in the tree to ensure the hosts
   * will be created at the right order when mounting. The host markers will be define which host
   * each mounted artifacts will be attached to.
   *
   * At this stage all the [LithoNode] for which we have LayoutOutputs that can be recycled will
   * have a DiffNode associated. If the CachedMeasures are valid we'll try to recycle both the host
   * and the contents (including background/foreground). In all other cases instead we'll only try
   * to re-use the hosts. In some cases the host's structure might change between two updates even
   * if the component is of the same type. This can happen for example when a click listener is
   * added. To avoid trying to re-use the wrong host type we explicitly check that after all the
   * children for a subtree have been added (this is when the actual host type is resolved). If the
   * host type changed compared to the one in the DiffNode we need to refresh the ids for the whole
   * subtree in order to ensure that the MountState will unmount the subtree and mount it again on
   * the correct host.
   *
   * @param parentContext the parent component context
   * @param result InternalNode to process.
   * @param reductionState the ReductionState currently operating.
   * @param parent the parent render tree node.
   * @param parentDiffNode whether this method also populates the diff tree and assigns the root
   * @param parentHierarchy The parent hierarchy linked list or null.
   */
  private fun collectResults(
      parentContext: ComponentContext,
      result: LayoutResult,
      reductionState: ReductionState,
      lithoLayoutContext: LithoLayoutContext,
      x: Int,
      y: Int,
      parent: RenderTreeNode? = null,
      parentDiffNode: DiffNode? = null,
      parentHierarchy: DebugHierarchy.Node? = null
  ) {
    if (lithoLayoutContext.isFutureReleased ||
        result !is LithoLayoutResult ||
        result.measureHadExceptions) {
      // Exit early if the layout future as been released or if this result had exceptions.
      return
    }

    val node: LithoNode = result.node
    val hierarchy: DebugHierarchy.Node? = node.getDebugHierarchy(parentHierarchy)

    if (result is NestedTreeHolderResult) {
      val size: Int = node.componentCount
      val immediateParentContext: ComponentContext =
          if (size == 1) {
            parentContext
          } else {
            node.getComponentContextAt(1)
          }

      val nestedTree: LithoLayoutResult = result.nestedResult ?: return

      collectResults(
          parentContext = immediateParentContext,
          result = nestedTree,
          reductionState = reductionState,
          lithoLayoutContext = lithoLayoutContext,
          x = x + result.getXForChildAtIndex(0), // Account for position of the holder node.
          y = y + result.getYForChildAtIndex(0), // Account for position of the holder node.
          parent = parent,
          parentDiffNode = parentDiffNode,
          parentHierarchy = hierarchy)
      return
    }

    val tail: ScopedComponentInfo = node.tailScopedComponentInfo
    val context: ComponentContext = tail.context
    val diffNode = createDiffNode(tail, parentDiffNode)
    if (parentDiffNode == null) {
      reductionState.diffTreeRoot = diffNode
    }

    var parentRenderTreeNode: RenderTreeNode? = parent
    val hostRenderUnit: LithoRenderUnit? =
        if (parentRenderTreeNode == null /* isRoot */) {
          LithoNodeUtils.createRootHostRenderUnit(result.node)
        } else {
          result.hostRenderUnit
        }
    val needsHostView: Boolean = (hostRenderUnit != null)
    val currentTransitionId: TransitionId? = reductionState.currentTransitionId
    val currentLayoutOutputAffinityGroup: OutputUnitsAffinityGroup<AnimatableItem>? =
        reductionState.currentLayoutOutputAffinityGroup

    reductionState.currentTransitionId = node.transitionId

    reductionState.currentLayoutOutputAffinityGroup =
        if (reductionState.currentTransitionId != null) OutputUnitsAffinityGroup() else null

    // create bounds
    val l: Int = x
    val t: Int = y
    val r: Int = l + result.width
    val b: Int = t + result.height
    val bounds = Rect(l, t, r, b)

    // 1. Insert a host LayoutOutput if we have some interactive content to be attached to.
    if (hostRenderUnit != null) {
      val hostLayoutPosition =
          addHostRenderTreeNode(
              hostRenderUnit = hostRenderUnit,
              bounds = bounds,
              parent = parentRenderTreeNode,
              result = result,
              node = node,
              reductionState = reductionState,
              diffNode = diffNode,
              hierarchy = hierarchy)
      addCurrentAffinityGroupToTransitionMapping(reductionState)

      parentRenderTreeNode = reductionState.mountableOutputs[hostLayoutPosition]
    }

    // 2. Add background if defined.
    if (!context.mLithoConfiguration.componentsConfig.shouldAddRootHostViewOrDisableBgFgOutputs) {
      result.backgroundRenderUnit?.let { backgroundRenderUnit ->
        val backgroundRenderTreeNode =
            addDrawableRenderTreeNode(
                unit = backgroundRenderUnit,
                bounds = bounds,
                parent = parentRenderTreeNode,
                result = result,
                reductionState = reductionState,
                expandedTouchBounds = result.expandedTouchBounds,
                hierarchy = hierarchy,
                type = OutputUnitType.BACKGROUND,
                matchHostBoundsTransitions = needsHostView)

        diffNode?.backgroundOutput = backgroundRenderTreeNode.renderUnit as LithoRenderUnit
      }
    }

    // Generate the RenderTreeNode for the given node.
    val contentRenderTreeNode: RenderTreeNode? =
        createContentRenderTreeNode(
            result = result,
            node = node,
            bounds = bounds,
            reductionState = reductionState,
            expandedTouchBounds = result.expandedTouchBounds,
            parent = parentRenderTreeNode,
            debugHierarchyNode = hierarchy)

    // 3. Now add the MountSpec (either View or Drawable) to the outputs.
    contentRenderTreeNode?.let { treeNode ->
      val contentRenderUnit: LithoRenderUnit = treeNode.renderUnit as LithoRenderUnit

      addRenderTreeNode(
          reductionState = reductionState,
          node = treeNode,
          result = result,
          unit = contentRenderUnit,
          type = OutputUnitType.CONTENT,
          transitionId = if (!needsHostView) reductionState.currentTransitionId else null,
          parent = parentRenderTreeNode)

      diffNode?.contentOutput = contentRenderUnit
    }

    // Set the measurements, and the layout data on the diff node
    if (diffNode != null) {
      diffNode.lastWidthSpec = result.widthSpec
      diffNode.lastHeightSpec = result.heightSpec
      diffNode.lastMeasuredWidth = result.contentWidth
      diffNode.lastMeasuredHeight = result.contentHeight
      diffNode.layoutData = result.layoutData
      diffNode.primitive = result.node.primitive
      diffNode.delegate = result.delegate
    }

    // We must process the nodes in order so that the layout state output order is correct.
    for (i in 0 until result.childCount) {
      val child: LithoLayoutResult = result.getChildAt(i)
      collectResults(
          parentContext = context,
          result = child,
          reductionState = reductionState,
          lithoLayoutContext = lithoLayoutContext,
          x = x + result.getXForChildAtIndex(i),
          y = y + result.getYForChildAtIndex(i),
          parent = parentRenderTreeNode,
          parentDiffNode = diffNode,
          parentHierarchy = hierarchy)
    }

    // 5. Add border color if defined.
    result.borderRenderUnit?.let { borderRenderUnit ->
      val borderRenderTreeNode: RenderTreeNode =
          addDrawableRenderTreeNode(
              unit = borderRenderUnit,
              bounds = bounds,
              parent = parentRenderTreeNode,
              result = result,
              reductionState = reductionState,
              expandedTouchBounds = result.expandedTouchBounds,
              hierarchy = hierarchy,
              type = OutputUnitType.BORDER,
              matchHostBoundsTransitions = needsHostView)

      diffNode?.borderOutput = borderRenderTreeNode.renderUnit as LithoRenderUnit
    }

    // 6. Add foreground if defined.
    if (!context.mLithoConfiguration.componentsConfig.shouldAddRootHostViewOrDisableBgFgOutputs) {
      result.foregroundRenderUnit?.let { foregroundRenderUnit ->
        val foregroundRenderTreeNode: RenderTreeNode =
            addDrawableRenderTreeNode(
                unit = foregroundRenderUnit,
                bounds = bounds,
                parent = parentRenderTreeNode,
                result = result,
                reductionState = reductionState,
                expandedTouchBounds = result.expandedTouchBounds,
                hierarchy = hierarchy,
                type = OutputUnitType.FOREGROUND,
                matchHostBoundsTransitions = needsHostView)

        diffNode?.foregroundOutput = foregroundRenderTreeNode.renderUnit as LithoRenderUnit
      }
    }

    // 7. Add VisibilityOutputs if any visibility-related event handlers are present.
    if (node.hasVisibilityHandlers()) {
      val visibilityOutput: VisibilityOutput =
          createVisibilityOutput(
              node = node,
              bounds = bounds,
              renderTreeNode =
                  contentRenderTreeNode ?: if (needsHostView) parentRenderTreeNode else null)

      reductionState.visibilityOutputs.add(visibilityOutput)
      diffNode?.visibilityOutput = visibilityOutput
    }

    // 8. If we're in a testing environment, maintain an additional data structure with
    // information about nodes that we can query later.
    if (reductionState.testOutputs != null && !node.testKey.isNullOrEmpty()) {
      val testOutput: TestOutput =
          createTestOutput(
              node = node,
              bounds = bounds,
              renderUnit = contentRenderTreeNode?.renderUnit as? LithoRenderUnit)
      reductionState.testOutputs.add(testOutput)
    }

    // collect the adjusted bounds of the content render node if it exists
    val rect: Rect = contentRenderTreeNode?.getAbsoluteBounds(Rect()) ?: bounds

    for (i in 0 until node.componentCount) {
      val delegate: Component = node.getComponentAt(i)
      val delegateKey: String? = node.getGlobalKeyAt(i)
      // Keep a list of the components we created during this layout calculation. If the layout is
      // valid, the ComponentTree will update the event handlers that have been created in the
      // previous ComponentTree with the new component dispatched, otherwise Section children
      // might not be accessing the correct props and state on the event handlers. The null
      // checkers cover tests, the scope and tree should not be null at this point of the layout
      // calculation.
      node.getComponentContextAt(i).let { delegateScopedContext ->
        if (delegate is SpecGeneratedComponent) {
          reductionState.scopedSpecComponentInfos?.add(delegateScopedContext.scopedComponentInfo)
        }
      }
      if (delegateKey != null || delegate.hasHandle()) {
        val copyRect = Rect(rect)
        if (delegateKey != null) {
          reductionState.componentKeyToBounds[delegateKey] = copyRect
        }
        if (delegate.hasHandle()) {
          val handle: Handle? = delegate.handle
          if (handle != null) {
            reductionState.componentHandleToBounds[handle] = copyRect
          }
        }
      }
    }

    addCurrentAffinityGroupToTransitionMapping(reductionState)
    reductionState.currentTransitionId = currentTransitionId
    reductionState.currentLayoutOutputAffinityGroup = currentLayoutOutputAffinityGroup
  }

  private fun addDrawableRenderTreeNode(
      unit: LithoRenderUnit,
      bounds: Rect,
      parent: RenderTreeNode? = null,
      result: LayoutResult,
      reductionState: ReductionState,
      expandedTouchBounds: Rect?,
      hierarchy: DebugHierarchy.Node? = null,
      @OutputUnitType type: Int,
      matchHostBoundsTransitions: Boolean
  ): RenderTreeNode {

    val debugNode: DebugHierarchy.Node? = hierarchy?.mutateType(type)
    val renderTreeNode: RenderTreeNode =
        createRenderTreeNode(
            unit = unit,
            bounds = bounds,
            reductionState = reductionState,
            isSizeDependant = true,
            expandedTouchBounds = expandedTouchBounds,
            parent = parent,
            debugHierarchyNode = debugNode)
    val drawableRenderUnit: LithoRenderUnit = renderTreeNode.renderUnit as LithoRenderUnit

    addRenderTreeNode(
        reductionState = reductionState,
        node = renderTreeNode,
        result = result,
        unit = drawableRenderUnit,
        type = type,
        transitionId =
            if (!matchHostBoundsTransitions) reductionState.currentTransitionId else null,
        parent = parent)

    return renderTreeNode
  }

  private fun addLayoutOutputIdToPositionsMap(
      outputsIdToPositionMap: LongSparseArray<Int>,
      unit: LithoRenderUnit,
      position: Int
  ) = outputsIdToPositionMap.put(unit.id, position)

  private fun maybeAddLayoutOutputToAffinityGroup(
      group: OutputUnitsAffinityGroup<AnimatableItem>? = null,
      @OutputUnitType outputType: Int,
      animatableItem: AnimatableItem
  ) = group?.add(outputType, animatableItem)

  private fun addCurrentAffinityGroupToTransitionMapping(reductionState: ReductionState) {
    val group: OutputUnitsAffinityGroup<AnimatableItem>? =
        reductionState.currentLayoutOutputAffinityGroup
    if (group == null || group.isEmpty) {
      return
    }

    val transitionId = reductionState.currentTransitionId ?: return

    if (transitionId.mType == TransitionId.Type.AUTOGENERATED) {
      // Check if the duplications of this key has been found before, if so, just ignore it
      if (!reductionState.duplicatedTransitionIds.contains(transitionId)) {
        if (reductionState.transitionIdMapping.put(transitionId, group) != null) {
          // Already seen component with the same generated transition key, remove it from the
          // mapping and ignore in the future
          reductionState.transitionIdMapping.remove(transitionId)
          reductionState.duplicatedTransitionIds.add(transitionId)
        }
      }
    } else {
      if (reductionState.transitionIdMapping.put(transitionId, group) != null) {
        // Already seen component with the same manually set transition key
        ComponentsReporter.emitMessage(
            ComponentsReporter.LogLevel.FATAL,
            DUPLICATE_TRANSITION_IDS,
            """The transitionId '$transitionId' is defined multiple times in the same layout. TransitionIDs must be unique.
                  Tree:
                  ${ComponentUtils.treeToString(reductionState.rootNode)}
                  """
                .trimIndent())
      }
    }
    reductionState.currentLayoutOutputAffinityGroup = null
    reductionState.currentTransitionId = null
  }

  /**
   * If we have an interactive LayoutSpec or a MountSpec Drawable, we need to insert an
   * HostComponent in the Outputs such as it will be used as a HostView at Mount time. View
   * MountSpec are not allowed.
   *
   * @return The position the HostLayoutOutput was inserted.
   */
  private fun addHostRenderTreeNode(
      hostRenderUnit: LithoRenderUnit,
      bounds: Rect,
      parent: RenderTreeNode? = null,
      result: LithoLayoutResult,
      node: LithoNode,
      reductionState: ReductionState,
      diffNode: DiffNode? = null,
      hierarchy: DebugHierarchy.Node? = null,
  ): Int {

    // Only the root host is allowed to wrap view mount specs as a layout output
    // is unconditionally added for it.
    require(!(node.willMountView && !reductionState.isLayoutRoot(result))) {
      "We shouldn't insert a host as a parent of a View"
    }
    val hostRenderTreeNode: RenderTreeNode =
        createHostRenderTreeNode(
            unit = hostRenderUnit,
            bounds = bounds,
            reductionState = reductionState,
            expandedTouchBounds = result.expandedTouchBounds,
            parent = parent,
            hierarchy = hierarchy)

    diffNode?.hostOutput = hostRenderUnit

    // The component of the hostLayoutOutput will be set later after all the
    // children got processed.
    addRenderTreeNode(
        reductionState = reductionState,
        node = hostRenderTreeNode,
        result = result,
        unit = hostRenderUnit,
        type = OutputUnitType.HOST,
        transitionId = reductionState.currentTransitionId,
        parent = parent)

    return reductionState.mountableOutputs.size - 1
  }

  private fun sortTops(reductionState: ReductionState) {
    val unsorted: List<IncrementalMountOutput> = ArrayList(reductionState.mountableOutputTops)
    try {
      reductionState.mountableOutputTops.sortWith(
          IncrementalMountRenderCoreExtension.sTopsComparator)
    } catch (e: IllegalArgumentException) {
      val errorMessage = StringBuilder()
      errorMessage.append(e.message).append("\n")
      val size = unsorted.size
      errorMessage.append("Error while sorting ReductionState tops. Size: $size").append("\n")
      val rect = Rect()
      for (i in 0 until size) {
        val node: RenderTreeNode = reductionState.mountableOutputs.get(i)
        errorMessage.append("   Index $i top: ${node.getAbsoluteBounds(rect).top}").append("\n")
      }
      throw IllegalStateException(errorMessage.toString())
    }
  }

  private fun sortBottoms(reductionState: ReductionState) {
    val unsorted: List<IncrementalMountOutput> = ArrayList(reductionState.mountableOutputBottoms)
    try {
      reductionState.mountableOutputBottoms.sortWith(
          IncrementalMountRenderCoreExtension.sBottomsComparator)
    } catch (e: IllegalArgumentException) {
      val errorMessage = StringBuilder()
      errorMessage.append(e.message).append("\n")
      val size = unsorted.size
      errorMessage.append("Error while sorting ReductionState bottoms. Size: $size").append("\n")
      val rect = Rect()
      for (i in 0 until size) {
        val node: RenderTreeNode = reductionState.mountableOutputs.get(i)
        errorMessage
            .append("   Index $i bottom: ${node.getAbsoluteBounds(rect).bottom}")
            .append("\n")
      }
      throw IllegalStateException(errorMessage.toString())
    }
  }

  private fun addRenderTreeNode(
      reductionState: ReductionState,
      node: RenderTreeNode,
      result: LayoutResult? = null,
      unit: LithoRenderUnit,
      @OutputUnitType type: Int,
      transitionId: TransitionId? = null,
      parent: RenderTreeNode? = null,
  ) {

    parent?.child(node)

    val component: Component = unit.component
    if (component is SpecGeneratedComponent &&
        component.implementsExtraAccessibilityNodes() &&
        unit.isAccessible &&
        parent != null) {
      val parentUnit: LithoRenderUnit = LithoRenderUnit.getRenderUnit(parent)
      (parentUnit.component as HostComponent).setImplementsVirtualViews()
    }

    val position: Int = reductionState.mountableOutputs.size
    val absoluteBounds: Rect = node.getAbsoluteBounds(Rect())
    val shouldExcludePrimitiveFromIncrementalMount: Boolean =
        unit.findAttachBinderByClass(ExcludeFromIncrementalMountBinder::class.java) != null
    val shouldExcludeSpecGeneratedComponentFromIncrementalMount: Boolean =
        component is SpecGeneratedComponent && component.excludeFromIncrementalMount()

    val incrementalMountOutput =
        IncrementalMountOutput(
            node.renderUnit.id,
            position,
            absoluteBounds,
            shouldExcludeSpecGeneratedComponentFromIncrementalMount ||
                shouldExcludePrimitiveFromIncrementalMount,
            if (parent != null) reductionState.incrementalMountOutputs[parent.renderUnit.id]
            else null)
    if (shouldExcludeSpecGeneratedComponentFromIncrementalMount ||
        shouldExcludePrimitiveFromIncrementalMount) {
      reductionState.hasComponentsExcludedFromIncrementalMount = true
    }

    val id: Long = node.renderUnit.id
    reductionState.mountableOutputs.add(node)
    reductionState.incrementalMountOutputs[id] = incrementalMountOutput
    reductionState.mountableOutputTops.add(incrementalMountOutput)
    reductionState.mountableOutputBottoms.add(incrementalMountOutput)

    if ((component is SpecGeneratedComponent && component.hasChildLithoViews()) ||
        node.renderUnit.doesMountRenderTreeHosts()) {
      reductionState.renderUnitIdsWhichHostRenderTrees.add(id)
    }

    val attrs: ViewAttributes? =
        LithoNodeUtils.createViewAttributes(
            unit,
            component,
            result,
            type,
            unit.importantForAccessibility,
            reductionState.componentContext.lithoConfiguration.componentsConfig
                .shouldAddRootHostViewOrDisableBgFgOutputs)

    if (attrs != null) {
      reductionState.renderUnitsWithViewAttributes[id] = attrs
    }

    if (node.renderUnit is LithoRenderUnit) {
      val lithoRenderUnit: LithoRenderUnit = node.renderUnit as LithoRenderUnit
      lithoRenderUnit.commonDynamicProps?.let { commonDynamicProps ->
        reductionState.dynamicValueOutputs.put(
            lithoRenderUnit.id,
            DynamicValueOutput(
                component = lithoRenderUnit.component,
                scopedContext = lithoRenderUnit.componentContext,
                commonDynamicProps = commonDynamicProps))
      }
    }

    val animatableItem =
        createAnimatableItem(
            unit,
            if (parent == null && (reductionState.rootX != 0 || reductionState.rootY != 0)) {
              Rect(
                  reductionState.rootX,
                  reductionState.rootY,
                  reductionState.rootX + absoluteBounds.width(),
                  reductionState.rootY + absoluteBounds.height(),
              )
            } else {
              absoluteBounds
            },
            type,
            transitionId)
    reductionState.animatableItems.put(node.renderUnit.id, animatableItem)
    addLayoutOutputIdToPositionsMap(reductionState.outputsIdToPositionMap, unit, position)
    maybeAddLayoutOutputToAffinityGroup(
        reductionState.currentLayoutOutputAffinityGroup, type, animatableItem)
  }

  private fun LithoNode.getDebugHierarchy(
      parentHierarchy: DebugHierarchy.Node? = null,
  ): DebugHierarchy.Node? {
    if (!LithoDebugConfigurations.isDebugHierarchyEnabled) {
      return null
    }
    val infos: List<ScopedComponentInfo> = scopedComponentInfos
    val components: MutableList<Component> = ArrayList(infos.size)
    for (info in infos) {
      components.add(info.component)
    }
    return DebugHierarchy.newNode(parentHierarchy, tailComponent, components)
  }
}
