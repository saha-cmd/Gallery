package com.gallery.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

// Responsive column-tier step thresholds for instant multi-step continuous zooming (5 <-> 4 <-> 3 <-> 2)
private const val STEP_ZOOM_IN_THRESHOLD = 1.15f
private const val STEP_ZOOM_OUT_THRESHOLD = 0.87f
private const val RELEASE_COMMIT_PROGRESS_THRESHOLD = 0.30f

// Subtle entrance scale for the solid (alpha = 1f) incoming bottom grid layer
private const val INCOMING_ZOOM_OUT_START_SCALE = 0.94f
private const val INCOMING_ZOOM_IN_START_SCALE = 1.06f

private class IntSizeHolder(var value: IntSize = IntSize.Zero)

private class GridLayer(
    val id: Int,
    val columns: Int,
    val state: LazyGridState
)

/**
 * Target tile width/height aspect ratio for a settled column density.
 * Zoomed-in densities (2–3 columns) settle smoothly to a taller 0.80f portrait ratio;
 * balanced/dense densities (4–7 columns) use 1:1 square tiles.
 */
fun gridTileAspectRatio(columns: Int): Float = if (columns <= 3) 0.80f else 1f

/**
 * Adaptive thumbnail decode target size (in pixels) scaled to grid density so zoomed-in
 * tiles stay sharp while 5–7 column overviews decode rapidly with minimal memory.
 */
fun gridThumbnailTargetSize(columns: Int): Int = when {
    columns <= 2 -> 260
    columns == 3 -> 200
    columns == 4 -> 150
    else -> 110
}

/**
 * Finger-tracked, One UI-style continuous pinch-to-zoom & pinch-to-zoom-out grid container.
 *
 * - **Zero-latency pinch engagement (<16ms):** Activates immediately on the first frame with 2+
 *   pressed pointers—no single-finger touchSlop delay, no zoom deadzone—while never consuming
 *   single-finger events so 1-finger `LazyVerticalGrid` scrolling and `VerticalFastScrollbar`
 *   stay locked at 60/120fps.
 * - **Continuous multi-step zoom (`1.15f` / `0.87f`):** Smoothly steps across multiple column
 *   densities (`7 <-> 6 <-> 5 <-> 4 <-> 3 <-> 2`) in a single pinch with haptic ticks, promoting
 *   the already-mounted incoming layer in place via `key(layer.id)` with zero recomposition or jump.
 * - **Solid incoming base + fading outgoing top layer:** Keeps the bottom incoming grid at solid
 *   `alpha = 1f` (`0.94f -> 1.0f` on zoom-out, `1.06f -> 1.0f` on zoom-in) while the top outgoing
 *   layer scales with fingers and fades `1f -> 0f` over an opaque layer backing—eliminating
 *   double-exposure ghosting and background bleed.
 * - **Locked target on finger lift:** Immediately locks `committedTargetColumns` and stops reading
 *   zoom deltas the instant pressed pointer count drops below 2, settling via a critically damped
 *   spring (`Spring.DampingRatioNoBouncy`, `Spring.StiffnessMedium`) with zero bounce-back snap.
 */
@Composable
fun AnimatedPinchGridContainer(
    currentColumns: Int,
    onColumnsChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minColumns: Int = 2,
    maxColumns: Int = 7,
    enabled: Boolean = true,
    gridState: LazyGridState? = null,
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
    onScrollPositionChanged: ((index: Int, offset: Int) -> Unit)? = null,
    onResolveTargetAnchorIndex: ((sourceColumns: Int, targetColumns: Int, sourceAnchorIndex: Int) -> Int)? = null,
    overlayContent: @Composable BoxScope.(activeGridState: LazyGridState, activeColumns: Int) -> Unit = { _, _ -> },
    gridContent: @Composable (columns: Int, state: LazyGridState, isPreviewLayer: Boolean, tileAspectRatio: Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val containerBgColor = MaterialTheme.colorScheme.background

    val initialColumns = currentColumns.coerceIn(minColumns, maxColumns)
    var nextLayerId by remember { mutableIntStateOf(2) }
    var activeLayer by remember {
        mutableStateOf(
            GridLayer(
                id = 1,
                columns = initialColumns,
                state = gridState ?: createViewportOnlyLazyGridState(
                    firstVisibleItemIndex = initialFirstVisibleItemIndex,
                    firstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset
                )
            )
        )
    }
    var incomingLayer by remember { mutableStateOf<GridLayer?>(null) }

    // Track last external column count so DataStore echoes never reset our promoted activeLayer
    var lastCommittedColumns by remember { mutableIntStateOf(initialColumns) }
    var lastObservedExternalColumns by remember { mutableIntStateOf(initialColumns) }

    val activeColumns = activeLayer.columns.coerceIn(minColumns, maxColumns)

    val latestOnScrollPositionChanged by rememberUpdatedState(onScrollPositionChanged)

    // Continuously sync active layer scroll position as user scrolls
    LaunchedEffect(activeLayer.state) {
        val currentState = activeLayer.state
        snapshotFlow {
            Triple(
                currentState.firstVisibleItemIndex,
                currentState.firstVisibleItemScrollOffset,
                currentState.isScrollInProgress
            )
        }.collect { (idx, off, isScrolling) ->
            if (isScrolling || idx > 0 || off > 0) {
                latestOnScrollPositionChanged?.invoke(idx, off)
            }
        }
    }

    // Smoothly interpolate vertical tile proportions across 3 <-> 4 columns without mid-pinch jumps
    val animatedTileAspectRatio by animateFloatAsState(
        targetValue = gridTileAspectRatio(activeColumns),
        animationSpec = tween(durationMillis = 180),
        label = "gridTileAspectRatio"
    )

    val latestActiveLayer by rememberUpdatedState(activeLayer)
    val latestIncomingLayer by rememberUpdatedState(incomingLayer)
    val latestAspectRatio by rememberUpdatedState(animatedTileAspectRatio)
    val latestMinColumns by rememberUpdatedState(minColumns)
    val latestMaxColumns by rememberUpdatedState(maxColumns)
    val latestOnColumnsChanged by rememberUpdatedState(onColumnsChanged)
    val latestResolveAnchor by rememberUpdatedState(onResolveTargetAnchorIndex)

    // Non-snapshot holder for container dimensions (never triggers recomposition on measure)
    val containerSizeHolder = remember { IntSizeHolder() }

    // Transition lifecycle states (untouched during normal 1-finger scrolling)
    var isPinching by remember { mutableStateOf(false) }
    var isSettling by remember { mutableStateOf(false) }
    val latestIsSettling by rememberUpdatedState(isSettling)

    // High-frequency animation states read strictly inside graphicsLayer lambdas (zero recomposition)
    val progressState = remember { mutableFloatStateOf(0f) }
    val rubberBandScaleState = remember { mutableFloatStateOf(1f) }
    val lockedPivotState = remember { mutableStateOf(TransformOrigin.Center) }

    val isTransitionActive = isPinching || isSettling

    // Sync external column changes (e.g. from Settings screen) without disrupting pinch commits
    LaunchedEffect(currentColumns) {
        val clamped = currentColumns.coerceIn(minColumns, maxColumns)
        if (clamped == lastCommittedColumns && clamped == activeLayer.columns) {
            lastObservedExternalColumns = clamped
            return@LaunchedEffect
        }
        val prevCols = lastObservedExternalColumns
        lastObservedExternalColumns = clamped
        lastCommittedColumns = clamped
        if (!isPinching && !isSettling && clamped != activeLayer.columns) {
            val currentState = activeLayer.state
            val currentIndex = currentState.firstVisibleItemIndex
            val currentOffset = currentState.firstVisibleItemScrollOffset
            val resolvedIndex = if (currentIndex > 0 || currentOffset > 0) {
                (latestResolveAnchor?.invoke(prevCols, clamped, currentIndex) ?: currentIndex).coerceAtLeast(0)
            } else {
                0
            }
            activeLayer = GridLayer(
                id = nextLayerId++,
                columns = clamped,
                state = createViewportOnlyLazyGridState(
                    firstVisibleItemIndex = resolvedIndex,
                    firstVisibleItemScrollOffset = 0
                )
            )
        }
    }

    fun lockPivotFromCentroid(centroid: Offset) {
        val size = containerSizeHolder.value
        val pivotX = if (size.width > 0) {
            (centroid.x / size.width.toFloat()).coerceIn(0.15f, 0.85f)
        } else {
            0.5f
        }
        val pivotY = if (size.height > 0) {
            (centroid.y / size.height.toFloat()).coerceIn(0.15f, 0.85f)
        } else {
            0.5f
        }
        lockedPivotState.value = TransformOrigin(pivotX, pivotY)
    }

    fun computeTargetTopIndex(
        sourceState: LazyGridState,
        sourceColumns: Int,
        targetColumns: Int,
        sourceAnchorIndex: Int,
        centroid: Offset,
        tileAspectRatio: Float
    ): Int {
        if (sourceState.firstVisibleItemIndex == 0 && sourceState.firstVisibleItemScrollOffset == 0) {
            return 0
        }
        val resolvedAnchor = latestResolveAnchor?.invoke(
            sourceColumns,
            targetColumns,
            sourceAnchorIndex
        ) ?: sourceAnchorIndex

        val viewportWidth = sourceState.layoutInfo.viewportSize.width.takeIf { it > 0 }
            ?: containerSizeHolder.value.width
        if (viewportWidth <= 0 || targetColumns <= 0) {
            return resolvedAnchor.coerceAtLeast(0)
        }
        val tileWidth = viewportWidth.toFloat() / targetColumns.toFloat()
        val tileHeight = (tileWidth / tileAspectRatio.coerceAtLeast(0.5f)).coerceAtLeast(1f)
        val rowsAboveCentroid = (centroid.y / tileHeight).toInt().coerceAtLeast(0)
        return (resolvedAnchor - rowsAboveCentroid * targetColumns).coerceAtLeast(0)
    }

    fun ensureIncomingLayer(
        sourceLayer: GridLayer,
        targetColumns: Int,
        sourceAnchorIndex: Int,
        centroid: Offset,
        tileAspectRatio: Float
    ): GridLayer {
        val existing = latestIncomingLayer
        if (existing != null && existing.columns == targetColumns) {
            return existing
        }
        val mappedTopIndex = computeTargetTopIndex(
            sourceState = sourceLayer.state,
            sourceColumns = sourceLayer.columns,
            targetColumns = targetColumns,
            sourceAnchorIndex = sourceAnchorIndex,
            centroid = centroid,
            tileAspectRatio = tileAspectRatio
        )
        val created = GridLayer(
            id = nextLayerId++,
            columns = targetColumns,
            state = createViewportOnlyLazyGridState(
                firstVisibleItemIndex = mappedTopIndex,
                firstVisibleItemScrollOffset = 0
            )
        )
        incomingLayer = created
        return created
    }

    fun promoteIncomingLayerImmediately(promoted: GridLayer) {
        Snapshot.withMutableSnapshot {
            activeLayer = promoted
            incomingLayer = null
            lastCommittedColumns = promoted.columns
            progressState.floatValue = 0f
            rubberBandScaleState.floatValue = 1f
        }
        latestOnColumnsChanged(promoted.columns)
    }

    fun launchReleaseSettlement() {
        val pendingIncoming = latestIncomingLayer
        val currentActive = latestActiveLayer
        val startProgress = progressState.floatValue
        val startRubberBand = rubberBandScaleState.floatValue

        // Lock committed target column immediately on finger lift so no bounce-back can occur
        val shouldCommitPending = pendingIncoming != null &&
            pendingIncoming.columns != currentActive.columns &&
            startProgress >= RELEASE_COMMIT_PROGRESS_THRESHOLD
        val committedTargetColumns = if (shouldCommitPending) {
            pendingIncoming.columns
        } else {
            currentActive.columns
        }

        if (shouldCommitPending) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        isSettling = true
        coroutineScope.launch {
            try {
                if (pendingIncoming != null) {
                    val anim = Animatable(startProgress)
                    anim.animateTo(
                        targetValue = if (shouldCommitPending) 1f else 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                            visibilityThreshold = 0.005f
                        )
                    ) {
                        progressState.floatValue = value.coerceIn(0f, 1f)
                    }
                } else if (startRubberBand != 1f) {
                    val anim = Animatable(startRubberBand)
                    anim.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                            visibilityThreshold = 0.002f
                        )
                    ) {
                        rubberBandScaleState.floatValue = value
                    }
                }

                if (shouldCommitPending) {
                    // Atomically promote the already-mounted incoming layer (same key, same LazyGridState,
                    // zero recomposition / zero scroll jump) and unmount the outgoing top layer.
                    Snapshot.withMutableSnapshot {
                        activeLayer = pendingIncoming
                        incomingLayer = null
                        lastCommittedColumns = committedTargetColumns
                        progressState.floatValue = 0f
                        rubberBandScaleState.floatValue = 1f
                        isSettling = false
                    }
                    latestOnColumnsChanged(committedTargetColumns)
                } else {
                    Snapshot.withMutableSnapshot {
                        incomingLayer = null
                        progressState.floatValue = 0f
                        rubberBandScaleState.floatValue = 1f
                        isSettling = false
                    }
                }
            } catch (_: Throwable) {
                Snapshot.withMutableSnapshot {
                    incomingLayer = null
                    progressState.floatValue = 0f
                    rubberBandScaleState.floatValue = 1f
                    isSettling = false
                }
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { containerSizeHolder.value = it }
            .pointerInput(enabled, minColumns, maxColumns) {
                if (!enabled) return@pointerInput

                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var gestureActive = false
                    var releaseLocked = false
                    var stepZoom = 1f
                    var currentBaseLayer = latestActiveLayer
                    var currentAnchorIndex = 0
                    var lockedCentroid = Offset.Zero

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val changes = event.changes
                        var pressedCount = 0
                        for (i in changes.indices) {
                            if (changes[i].pressed) {
                                pressedCount++
                            }
                        }

                        // Single-finger / all-up fast path
                        if (pressedCount == 0) {
                            if (gestureActive) {
                                for (i in changes.indices) {
                                    changes[i].consume()
                                }
                                if (!releaseLocked) {
                                    releaseLocked = true
                                    isPinching = false
                                    launchReleaseSettlement()
                                }
                            }
                            break
                        }

                        if (pressedCount == 1) {
                            if (gestureActive) {
                                // Pressed pointers dropped below 2: immediately lock target column
                                // and stop reading zoom deltas so peeling off fingers never rebounds.
                                if (!releaseLocked) {
                                    releaseLocked = true
                                    isPinching = false
                                    launchReleaseSettlement()
                                }
                                for (i in changes.indices) {
                                    changes[i].consume()
                                }
                            }
                            continue
                        }

                        // 2+ pointers active: ignore new pinch engagement while settling
                        if (latestIsSettling || releaseLocked) {
                            for (i in changes.indices) {
                                changes[i].consume()
                            }
                            continue
                        }

                        // Engage immediately on frame 1 when 2+ pointers press (zero deadzone)
                        if (!gestureActive) {
                            gestureActive = true
                            currentBaseLayer = latestActiveLayer
                            stepZoom = 1f

                            val initialCentroid = event.calculateCentroid(useCurrent = false)
                            lockedCentroid = if (initialCentroid != Offset.Unspecified) {
                                initialCentroid
                            } else {
                                Offset(
                                    containerSizeHolder.value.width * 0.5f,
                                    containerSizeHolder.value.height * 0.5f
                                )
                            }
                            lockPivotFromCentroid(lockedCentroid)
                            currentAnchorIndex = findAnchorIndexForCentroid(currentBaseLayer.state, lockedCentroid)

                            Snapshot.withMutableSnapshot {
                                progressState.floatValue = 0f
                                rubberBandScaleState.floatValue = 1f
                                incomingLayer = null
                                isPinching = true
                            }
                        }

                        val zoomChange = event.calculateZoom()
                        if (zoomChange.isFinite() && zoomChange > 0f && zoomChange != 1f) {
                            stepZoom *= zoomChange

                            val minCols = latestMinColumns
                            val maxCols = latestMaxColumns
                            val baseCols = currentBaseLayer.columns
                            val tileAspect = latestAspectRatio

                            if (stepZoom > 1f) {
                                // Zooming IN (fewer, larger columns)
                                if (baseCols > minCols) {
                                    val targetCols = (baseCols - 1).coerceAtLeast(minCols)
                                    val nextLayer = ensureIncomingLayer(
                                        sourceLayer = currentBaseLayer,
                                        targetColumns = targetCols,
                                        sourceAnchorIndex = currentAnchorIndex,
                                        centroid = lockedCentroid,
                                        tileAspectRatio = tileAspect
                                    )
                                    val stepProgress = ((stepZoom - 1f) / (STEP_ZOOM_IN_THRESHOLD - 1f)).coerceIn(0f, 1f)
                                    progressState.floatValue = stepProgress
                                    rubberBandScaleState.floatValue = 1f

                                    if (stepZoom >= STEP_ZOOM_IN_THRESHOLD) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        promoteIncomingLayerImmediately(nextLayer)
                                        currentBaseLayer = nextLayer
                                        currentAnchorIndex = findAnchorIndexForCentroid(nextLayer.state, lockedCentroid)
                                        stepZoom = 1f
                                    }
                                } else {
                                    // At minimum columns (2): subtle elastic resistance
                                    if (latestIncomingLayer != null) {
                                        incomingLayer = null
                                    }
                                    progressState.floatValue = 0f
                                    rubberBandScaleState.floatValue =
                                        1f + ((stepZoom - 1f) * 0.14f).coerceIn(0f, 0.07f)
                                }
                            } else if (stepZoom < 1f) {
                                // Zooming OUT (more, denser columns)
                                if (baseCols < maxCols) {
                                    val targetCols = (baseCols + 1).coerceAtMost(maxCols)
                                    val nextLayer = ensureIncomingLayer(
                                        sourceLayer = currentBaseLayer,
                                        targetColumns = targetCols,
                                        sourceAnchorIndex = currentAnchorIndex,
                                        centroid = lockedCentroid,
                                        tileAspectRatio = tileAspect
                                    )
                                    val stepProgress = ((1f - stepZoom) / (1f - STEP_ZOOM_OUT_THRESHOLD)).coerceIn(0f, 1f)
                                    progressState.floatValue = stepProgress
                                    rubberBandScaleState.floatValue = 1f

                                    if (stepZoom <= STEP_ZOOM_OUT_THRESHOLD) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        promoteIncomingLayerImmediately(nextLayer)
                                        currentBaseLayer = nextLayer
                                        currentAnchorIndex = findAnchorIndexForCentroid(nextLayer.state, lockedCentroid)
                                        stepZoom = 1f
                                    }
                                } else {
                                    // At maximum columns (7): subtle elastic resistance
                                    if (latestIncomingLayer != null) {
                                        incomingLayer = null
                                    }
                                    progressState.floatValue = 0f
                                    rubberBandScaleState.floatValue =
                                        1f + ((stepZoom - 1f) * 0.14f).coerceIn(-0.07f, 0f)
                                }
                            }
                        }

                        for (i in changes.indices) {
                            changes[i].consume()
                        }
                    }
                }
            }
            .then(if (isTransitionActive) Modifier.clipToBounds() else Modifier)
    ) {
        val topLayer = activeLayer
        val bottomLayer = incomingLayer
        val hasIncoming = isTransitionActive && bottomLayer != null
        val isZoomingIn = bottomLayer != null && bottomLayer.columns < topLayer.columns

        // Layer 2 (Top / Active): Emitted first with zIndex = 1f when transitioning so mounting the
        // incoming layer never shifts its slot position. Zero graphicsLayer when idle.
        key(topLayer.id) {
            Box(
                modifier = if (isTransitionActive) {
                    Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                        .graphicsLayer {
                            if (hasIncoming) {
                                val p = progressState.floatValue
                                val s = if (isZoomingIn) {
                                    lerp(1.0f, STEP_ZOOM_IN_THRESHOLD, p)
                                } else {
                                    lerp(1.0f, STEP_ZOOM_OUT_THRESHOLD, p)
                                }
                                alpha = (1f - p).coerceIn(0f, 1f)
                                scaleX = s
                                scaleY = s
                            } else {
                                val s = rubberBandScaleState.floatValue
                                alpha = 1f
                                scaleX = s
                                scaleY = s
                            }
                            transformOrigin = lockedPivotState.value
                        }
                        .background(containerBgColor)
                } else {
                    Modifier.fillMaxSize()
                }
            ) {
                gridContent(topLayer.columns, topLayer.state, false, animatedTileAspectRatio)
            }
        }

        // Layer 1 (Bottom / Incoming): Solid alpha = 1f at all times over an opaque background so
        // both grids are never semi-transparent simultaneously (zero double-exposure ghosting).
        // Promoted directly to activeLayer on commit via key(bottomLayer.id) with zero recomposition.
        if (isTransitionActive && bottomLayer != null) {
            key(bottomLayer.id) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(0f)
                        .background(containerBgColor)
                        .graphicsLayer {
                            val p = progressState.floatValue
                            val s = if (isZoomingIn) {
                                lerp(INCOMING_ZOOM_IN_START_SCALE, 1.0f, p)
                            } else {
                                lerp(INCOMING_ZOOM_OUT_START_SCALE, 1.0f, p)
                            }
                            alpha = 1f
                            scaleX = s
                            scaleY = s
                            transformOrigin = lockedPivotState.value
                        }
                ) {
                    gridContent(bottomLayer.columns, bottomLayer.state, true, animatedTileAspectRatio)
                }
            }
        }

        // Overlay slot (e.g. VerticalFastScrollbar) stays synced to the active LazyGridState & columns
        Box(modifier = Modifier.fillMaxSize().zIndex(2f)) {
            overlayContent(topLayer.state, topLayer.columns)
        }
    }
}

private fun findAnchorIndexForCentroid(gridState: LazyGridState, centroid: Offset): Int {
    val visibleItems = gridState.layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return gridState.firstVisibleItemIndex

    val firstIndex = gridState.firstVisibleItemIndex
    if (firstIndex == 0 && gridState.firstVisibleItemScrollOffset == 0) {
        return 0
    }

    var closestIndex = firstIndex
    var minDistSq = Float.MAX_VALUE
    for (i in visibleItems.indices) {
        val item = visibleItems[i]
        val centerX = item.offset.x + item.size.width * 0.5f
        val centerY = item.offset.y + item.size.height * 0.5f
        val dx = centerX - centroid.x
        val dy = centerY - centroid.y
        val distSq = dx * dx + dy * dy
        if (distSq < minDistSq) {
            minDistSq = distSq
            closestIndex = item.index
        }
    }
    return closestIndex
}
