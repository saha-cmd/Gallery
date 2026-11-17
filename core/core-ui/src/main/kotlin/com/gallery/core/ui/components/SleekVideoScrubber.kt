package com.gallery.core.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun SleekVideoScrubber(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    onScrubbingChanged: ((Boolean) -> Unit)? = null
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val currentOnScrubbingChanged by rememberUpdatedState(onScrubbingChanged)

    val minVal = valueRange.start
    val maxVal = valueRange.endInclusive
    val rangeSpan = (maxVal - minVal).coerceAtLeast(0.001f)

    val currentProgress = if (isDragging) {
        dragProgress
    } else {
        ((value - minVal) / rangeSpan).coerceIn(0f, 1f)
    }

    val trackHeight by animateDpAsState(
        targetValue = if (isDragging) 4.dp else 2.5.dp,
        animationSpec = tween(durationMillis = 150),
        label = "scrubberTrackHeight"
    )

    val thumbDiameter by animateDpAsState(
        targetValue = if (isDragging) 14.dp else 10.dp,
        animationSpec = tween(durationMillis = 150),
        label = "scrubberThumbSize"
    )

    val thumbElevation by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 2.dp,
        animationSpec = tween(durationMillis = 150),
        label = "scrubberThumbElevation"
    )

    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled, minVal, maxVal) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = { offset ->
                        val progress = (offset.x / widthPx).coerceIn(0f, 1f)
                        val newValue = minVal + progress * rangeSpan
                        currentOnScrubbingChanged?.invoke(true)
                        currentOnValueChange(newValue)
                        tryAwaitRelease()
                        currentOnScrubbingChanged?.invoke(false)
                        currentOnValueChangeFinished?.invoke()
                    }
                )
            }
            .pointerInput(enabled, minVal, maxVal) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragProgress = (offset.x / widthPx).coerceIn(0f, 1f)
                        currentOnScrubbingChanged?.invoke(true)
                        val newVal = minVal + dragProgress * rangeSpan
                        currentOnValueChange(newVal)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragProgress = (change.position.x / widthPx).coerceIn(0f, 1f)
                        val newVal = minVal + dragProgress * rangeSpan
                        currentOnValueChange(newVal)
                    },
                    onDragEnd = {
                        isDragging = false
                        currentOnScrubbingChanged?.invoke(false)
                        currentOnValueChangeFinished?.invoke()
                    },
                    onDragCancel = {
                        isDragging = false
                        currentOnScrubbingChanged?.invoke(false)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Inactive Track (full width, White 28% alpha)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.28f))
        )

        // Active Track (proportional width, MaterialTheme primary)
        Box(
            modifier = Modifier
                .fillMaxWidth(currentProgress)
                .height(trackHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )

        // Minimalist Thumb Dot with glow / elevation
        val thumbPx = with(density) { thumbDiameter.toPx() }
        val thumbOffsetPx = (currentProgress * widthPx - thumbPx / 2f)
            .coerceIn(0f, (widthPx - thumbPx).coerceAtLeast(0f))

        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffsetPx.roundToInt(), 0) }
                .size(thumbDiameter)
                .shadow(elevation = thumbElevation, shape = CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
