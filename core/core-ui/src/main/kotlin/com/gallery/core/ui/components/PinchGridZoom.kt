package com.gallery.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val GRID_PINCH_ZOOM_IN_THRESHOLD = 1.28f
private const val GRID_PINCH_ZOOM_OUT_THRESHOLD = 0.78f

/**
 * Enables two-finger pinch-to-zoom-in (fewer, larger columns) and pinch-to-zoom-out
 * (more, denser columns) on a media grid with zero single-finger scroll overhead.
 *
 * - Single-finger fast path: counts pressed pointers via an allocation-free indexed loop and
 *   immediately skips any gesture math, state writes, or event consumption when `pressedCount <= 1`.
 * - Layer-free idle state: `.clipToBounds().graphicsLayer { ... }` is attached only while actively
 *   pinching or springing back to `1.0f`, avoiding permanent hardware-layer compositing overhead
 *   over the scrolling `LazyVerticalGrid`.
 * - Multi-touch uses [PointerEventPass.Initial] so 2+ finger pinches are consumed before grid
 *   scrollables, item clickables, or tab pagers can intercept them.
 */
@Composable
fun Modifier.pinchToZoomGrid(
    currentColumns: Int,
    minColumns: Int = 2,
    maxColumns: Int = 7,
    enabled: Boolean = true,
    gridState: LazyGridState? = null,
    onColumnsChanged: (Int) -> Unit
): Modifier {
    if (!enabled) return this

    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val latestColumns by rememberUpdatedState(currentColumns)
    val latestMinColumns by rememberUpdatedState(minColumns)
    val latestMaxColumns by rememberUpdatedState(maxColumns)
    val latestOnColumnsChanged by rememberUpdatedState(onColumnsChanged)
    val latestGridState by rememberUpdatedState(gridState)

    // visualScaleState is read strictly inside the graphicsLayer lambda — never in composable scope
    val visualScaleState = remember { mutableFloatStateOf(1f) }
    var isPinchLayerActive by remember { mutableStateOf(false) }
    var springJob by remember { mutableStateOf<Job?>(null) }
    var previousColumns by remember { mutableIntStateOf(currentColumns) }
    var pendingAnchorIndex by remember { mutableStateOf<Int?>(null) }

    // Anchor scroll position only when column count actually changes (never on initial screen mount)
    LaunchedEffect(currentColumns) {
        if (currentColumns == previousColumns) return@LaunchedEffect
        previousColumns = currentColumns

        val targetIndex = pendingAnchorIndex ?: return@LaunchedEffect
        pendingAnchorIndex = null
        val state = latestGridState ?: return@LaunchedEffect
        val totalCount = state.layoutInfo.totalItemsCount
        if (totalCount > 0) {
            state.scrollToItem(
                index = targetIndex.coerceIn(0, totalCount - 1),
                scrollOffset = 0
            )
        }
    }

    val captureScrollAnchor: () -> Int? = {
        val state = latestGridState
        if (state == null) {
            null
        } else {
            val firstIndex = state.firstVisibleItemIndex
            val firstOffset = state.firstVisibleItemScrollOffset
            if (firstIndex == 0 && firstOffset == 0) {
                0
            } else {
                val viewportTop = state.layoutInfo.viewportStartOffset
                val visible = state.layoutInfo.visibleItemsInfo
                var anchor = firstIndex
                for (i in visible.indices) {
                    val item = visible[i]
                    if (item.offset.y >= viewportTop) {
                        anchor = item.index
                        break
                    }
                }
                anchor
            }
        }
    }

    val springVisualScaleToRest: () -> Unit = {
        springJob?.cancel()
        val startScale = visualScaleState.floatValue
        if (startScale == 1f) {
            isPinchLayerActive = false
        } else {
            springJob = coroutineScope.launch {
                try {
                    val progress = Animatable(0f)
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) {
                        visualScaleState.floatValue = lerp(startScale, 1f, value)
                    }
                    visualScaleState.floatValue = 1f
                } finally {
                    if (visualScaleState.floatValue == 1f) {
                        isPinchLayerActive = false
                    }
                }
            }
        }
    }

    return this
        .pointerInput(minColumns, maxColumns) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var isPinching = false
                var cumulativeZoom = 1f
                var gestureColumns = latestColumns

                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val changes = event.changes
                    var pressedCount = 0
                    for (i in changes.indices) {
                        if (changes[i].pressed) {
                            pressedCount++
                        }
                    }

                    if (pressedCount == 0) {
                        if (isPinching) {
                            for (i in changes.indices) {
                                changes[i].consume()
                            }
                        }
                        break
                    }

                    if (pressedCount == 1) {
                        if (isPinching) {
                            // Consume trailing 1-finger movement after pinch so scroll/tap don't misfire
                            for (i in changes.indices) {
                                changes[i].consume()
                            }
                        }
                        continue
                    }

                    // 2+ pointers active: handle pinch-to-zoom
                    if (!isPinching) {
                        isPinching = true
                        isPinchLayerActive = true
                    }

                    val zoomChange = event.calculateZoom()
                    if (zoomChange.isFinite() && zoomChange > 0f && zoomChange != 1f) {
                        cumulativeZoom *= zoomChange

                        val minCols = latestMinColumns
                        val maxCols = latestMaxColumns

                        if (cumulativeZoom >= GRID_PINCH_ZOOM_IN_THRESHOLD && gestureColumns > minCols) {
                            val nextCols = (gestureColumns - 1).coerceIn(minCols, maxCols)
                            gestureColumns = nextCols
                            pendingAnchorIndex = captureScrollAnchor()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnColumnsChanged(nextCols)
                            cumulativeZoom = 1f
                            springVisualScaleToRest()
                        } else if (cumulativeZoom <= GRID_PINCH_ZOOM_OUT_THRESHOLD && gestureColumns < maxCols) {
                            val nextCols = (gestureColumns + 1).coerceIn(minCols, maxCols)
                            gestureColumns = nextCols
                            pendingAnchorIndex = captureScrollAnchor()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnColumnsChanged(nextCols)
                            cumulativeZoom = 1f
                            springVisualScaleToRest()
                        } else {
                            val atMinBoundary = cumulativeZoom > 1f && gestureColumns <= minCols
                            val atMaxBoundary = cumulativeZoom < 1f && gestureColumns >= maxCols
                            val damping = if (atMinBoundary || atMaxBoundary) 0.08f else 0.20f
                            val maxDelta = if (atMinBoundary || atMaxBoundary) 0.025f else 0.06f
                            springJob?.cancel()
                            isPinchLayerActive = true
                            visualScaleState.floatValue = 1f + ((cumulativeZoom - 1f) * damping).coerceIn(-maxDelta, maxDelta)
                        }
                    }

                    for (i in changes.indices) {
                        changes[i].consume()
                    }
                }

                if (isPinching) {
                    springVisualScaleToRest()
                }
            }
        }
        .then(
            if (isPinchLayerActive) {
                Modifier
                    .clipToBounds()
                    .graphicsLayer {
                        val s = visualScaleState.floatValue
                        scaleX = s
                        scaleY = s
                    }
            } else {
                Modifier
            }
        )
}
