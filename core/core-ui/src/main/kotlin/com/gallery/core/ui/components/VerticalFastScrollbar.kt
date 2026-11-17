package com.gallery.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Interactive fast-scrollbar for [LazyGridState].
 */
@Composable
fun VerticalFastScrollbar(
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    labelProvider: ((firstVisibleIndex: Int) -> String)? = null
) {
    val totalItems by remember { derivedStateOf { gridState.layoutInfo.totalItemsCount } }
    val visibleItemsInfo by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo } }
    val firstVisibleIndex by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
    val isScrollInProgress by remember { derivedStateOf { gridState.isScrollInProgress } }

    val isOverflowing by remember {
        derivedStateOf {
            if (totalItems == 0 || visibleItemsInfo.isEmpty()) false
            else {
                val first = visibleItemsInfo.first().index
                val last = visibleItemsInfo.last().index
                (last - first + 1) < totalItems || first > 0
            }
        }
    }

    val scrollFraction by remember {
        derivedStateOf {
            if (!isOverflowing || totalItems <= visibleItemsInfo.size) 0f
            else {
                val maxFirst = (totalItems - visibleItemsInfo.size).coerceAtLeast(1)
                (firstVisibleIndex.toFloat() / maxFirst.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    FastScrollbarContent(
        isOverflowing = isOverflowing,
        scrollFraction = scrollFraction,
        isScrollInProgress = isScrollInProgress,
        totalItemsCount = totalItems,
        visibleItemsCount = visibleItemsInfo.size,
        currentFirstVisibleIndex = firstVisibleIndex,
        onScrollToItem = { index ->
            gridState.scrollToItem(index)
        },
        labelProvider = labelProvider,
        modifier = modifier
    )
}

/**
 * Interactive fast-scrollbar for [LazyListState].
 */
@Composable
fun VerticalFastScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    labelProvider: ((firstVisibleIndex: Int) -> String)? = null
) {
    val totalItems by remember { derivedStateOf { listState.layoutInfo.totalItemsCount } }
    val visibleItemsInfo by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo } }
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val isScrollInProgress by remember { derivedStateOf { listState.isScrollInProgress } }

    val isOverflowing by remember {
        derivedStateOf {
            if (totalItems == 0 || visibleItemsInfo.isEmpty()) false
            else {
                val first = visibleItemsInfo.first().index
                val last = visibleItemsInfo.last().index
                (last - first + 1) < totalItems || first > 0
            }
        }
    }

    val scrollFraction by remember {
        derivedStateOf {
            if (!isOverflowing || totalItems <= visibleItemsInfo.size) 0f
            else {
                val maxFirst = (totalItems - visibleItemsInfo.size).coerceAtLeast(1)
                (firstVisibleIndex.toFloat() / maxFirst.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    FastScrollbarContent(
        isOverflowing = isOverflowing,
        scrollFraction = scrollFraction,
        isScrollInProgress = isScrollInProgress,
        totalItemsCount = totalItems,
        visibleItemsCount = visibleItemsInfo.size,
        currentFirstVisibleIndex = firstVisibleIndex,
        onScrollToItem = { index ->
            listState.scrollToItem(index)
        },
        labelProvider = labelProvider,
        modifier = modifier
    )
}

/**
 * Interactive fast-scrollbar for [ScrollState] (e.g. Settings / Column forms).
 */
@Composable
fun VerticalFastScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    labelProvider: ((scrollOffset: Int) -> String)? = null
) {
    val maxValue by remember { derivedStateOf { scrollState.maxValue } }
    val currentValue by remember { derivedStateOf { scrollState.value } }
    val isScrollInProgress by remember { derivedStateOf { scrollState.isScrollInProgress } }

    val isOverflowing by remember { derivedStateOf { maxValue > 0 } }
    val scrollFraction by remember {
        derivedStateOf {
            if (maxValue > 0) (currentValue.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f) else 0f
        }
    }

    val labelAdapter = remember(labelProvider) {
        if (labelProvider == null) null
        else { index: Int ->
            val offset = (index.toFloat() / 1000f * maxValue).roundToInt().coerceIn(0, maxValue)
            labelProvider(offset)
        }
    }

    FastScrollbarContent(
        isOverflowing = isOverflowing,
        scrollFraction = scrollFraction,
        isScrollInProgress = isScrollInProgress,
        totalItemsCount = 1000,
        visibleItemsCount = 100,
        currentFirstVisibleIndex = (scrollFraction * 1000f).roundToInt(),
        onScrollToItem = { index ->
            val targetOffset = (index.toFloat() / 1000f * maxValue).roundToInt().coerceIn(0, maxValue)
            scrollState.scrollTo(targetOffset)
        },
        labelProvider = labelAdapter,
        modifier = modifier
    )
}

@Composable
private fun FastScrollbarContent(
    isOverflowing: Boolean,
    scrollFraction: Float,
    isScrollInProgress: Boolean,
    totalItemsCount: Int,
    visibleItemsCount: Int,
    currentFirstVisibleIndex: Int,
    onScrollToItem: suspend (Int) -> Unit,
    labelProvider: ((firstVisibleIndex: Int) -> String)?,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var isScrollbarActive by remember { mutableStateOf(false) }
    var dragProgressFraction by remember { mutableFloatStateOf(0f) }
    var activeTargetIndex by remember { mutableIntStateOf(currentFirstVisibleIndex) }

    LaunchedEffect(isScrollInProgress, isDragging) {
        if (isScrollInProgress || isDragging) {
            isScrollbarActive = true
        } else {
            delay(1500)
            isScrollbarActive = false
        }
    }

    val effectiveFraction = if (isDragging) dragProgressFraction else scrollFraction

    val alpha by animateFloatAsState(
        targetValue = if (isOverflowing && (isScrollbarActive || isDragging)) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "scrollbar_alpha"
    )

    val thumbWidth by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 4.dp,
        animationSpec = tween(durationMillis = 150),
        label = "thumb_width"
    )

    val thumbColor = if (isDragging) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (alpha > 0f) Modifier.alpha(alpha) else Modifier
            )
    ) {
        val viewportHeightPx = constraints.maxHeight.toFloat()
        if (viewportHeightPx <= 0f) return@BoxWithConstraints

        val minThumbHeightPx = with(density) { 48.dp.toPx() }
        val maxThumbHeightPx = with(density) { 140.dp.toPx() }

        val ratio = if (totalItemsCount > 0) {
            (visibleItemsCount.toFloat() / totalItemsCount.toFloat()).coerceIn(0.05f, 1f)
        } else 1f

        val calculatedThumbHeightPx = (viewportHeightPx * ratio).coerceIn(minThumbHeightPx, maxThumbHeightPx)
        val maxTravelPx = (viewportHeightPx - calculatedThumbHeightPx).coerceAtLeast(1f)
        val thumbOffsetYPx = (effectiveFraction * maxTravelPx).coerceIn(0f, maxTravelPx)

        // Floating label pill badge while dragging
        if (isDragging && labelProvider != null) {
            val labelText = remember(activeTargetIndex) {
                labelProvider(activeTargetIndex)
            }
            if (labelText.isNotBlank()) {
                val badgeOffsetY = with(density) {
                    (thumbOffsetYPx + calculatedThumbHeightPx / 2f - 18.dp.toPx())
                        .coerceIn(16.dp.toPx(), viewportHeightPx - 50.dp.toPx())
                        .toDp()
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 6.dp,
                    tonalElevation = 3.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-36).dp, y = badgeOffsetY)
                ) {
                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Hit-target area along right edge (width 28.dp, aligned CenterEnd)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(28.dp)
                .then(
                    if (isOverflowing) {
                        Modifier.pointerInput(totalItemsCount, maxTravelPx, calculatedThumbHeightPx) {
                            detectVerticalDragGestures(
                                onDragStart = { offset ->
                                    isDragging = true
                                    val clampedY = (offset.y - calculatedThumbHeightPx / 2f).coerceIn(0f, maxTravelPx)
                                    val fraction = clampedY / maxTravelPx
                                    dragProgressFraction = fraction
                                    val target = (fraction * (totalItemsCount - 1)).roundToInt().coerceIn(0, totalItemsCount - 1)
                                    activeTargetIndex = target
                                    coroutineScope.launch {
                                        onScrollToItem(target)
                                    }
                                },
                                onDragEnd = { isDragging = false },
                                onDragCancel = { isDragging = false },
                                onVerticalDrag = { change, _ ->
                                    change.consume()
                                    val clampedY = (change.position.y - calculatedThumbHeightPx / 2f).coerceIn(0f, maxTravelPx)
                                    val fraction = clampedY / maxTravelPx
                                    dragProgressFraction = fraction
                                    val target = (fraction * (totalItemsCount - 1)).roundToInt().coerceIn(0, totalItemsCount - 1)
                                    activeTargetIndex = target
                                    coroutineScope.launch {
                                        onScrollToItem(target)
                                    }
                                }
                            )
                        }
                    } else Modifier
                ),
            contentAlignment = Alignment.TopEnd
        ) {
            // Visual Pill Thumb
            Box(
                modifier = Modifier
                    .padding(end = 3.dp)
                    .offset { IntOffset(0, thumbOffsetYPx.roundToInt()) }
                    .size(
                        width = thumbWidth,
                        height = with(density) { calculatedThumbHeightPx.toDp() }
                    )
                    .clip(CircleShape)
                    .background(thumbColor)
            )
        }
    }
}
