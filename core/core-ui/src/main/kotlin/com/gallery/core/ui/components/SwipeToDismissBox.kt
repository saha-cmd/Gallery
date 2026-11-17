package com.gallery.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A container that allows dismissing a full-screen viewer by dragging/swiping downwards.
 *
 * When dragging down:
 * - Content translates downwards with subtle scaling down (up to 0.85x)
 * - Background color smoothly fades out
 * - When released past the threshold (> 160px), [onDismiss] is called.
 * - When released before the threshold, it springs back smoothly to origin.
 * - Direction-aware: Leaves horizontal drags (e.g. HorizontalPager) and zoom gestures unhindered.
 */
@Composable
fun SwipeToDismissBox(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSwipeUp: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val offsetY = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnSwipeUp by rememberUpdatedState(onSwipeUp)

    val dismissFraction = (offsetY.value / 500f).coerceIn(0f, 1f)
    val bgAlpha = (1f - dismissFraction * 0.75f).coerceIn(0f, 1f)
    val scale = (1f - dismissFraction * 0.15f).coerceIn(0.85f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bgAlpha))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val touchSlop = viewConfiguration.touchSlop

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalX = 0f
                    var totalY = 0f
                    var isDraggingDown = false
                    var isDraggingUp = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!change.pressed) {
                            if (isDraggingDown) {
                                if (offsetY.value > 160f) {
                                    currentOnDismiss()
                                } else {
                                    coroutineScope.launch {
                                        offsetY.animateTo(
                                            0f,
                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                        )
                                    }
                                }
                            } else if (isDraggingUp) {
                                if (totalY < -70f) {
                                    currentOnSwipeUp?.invoke()
                                }
                            }
                            break
                        }

                        // If not yet dragging, check if child component (HorizontalPager, Zoom, Slider) consumed gesture
                        if (!isDraggingDown && !isDraggingUp && event.changes.any { it.isConsumed }) {
                            break
                        }

                        val dx = change.position.x - change.previousPosition.x
                        val dy = change.position.y - change.previousPosition.y
                        totalX += dx
                        totalY += dy

                        if (!isDraggingDown && !isDraggingUp) {
                            val absX = abs(totalX)
                            val absY = abs(totalY)

                            if (absX > touchSlop || absY > touchSlop) {
                                if (absX >= absY) {
                                    // Horizontal swipe -> leave unconsumed for HorizontalPager
                                    break
                                } else if (totalY > touchSlop && totalY > absX * 1.25f) {
                                    // Definite downward drag to dismiss
                                    isDraggingDown = true
                                    change.consume()
                                    coroutineScope.launch {
                                        offsetY.snapTo(totalY)
                                    }
                                } else if (totalY < -touchSlop && absY > absX * 1.25f && currentOnSwipeUp != null) {
                                    // Definite upward drag to view info - consume gesture so other elements don't grab it
                                    // Do NOT move the image upward (offset stays at 0f)
                                    isDraggingUp = true
                                    change.consume()
                                } else {
                                    break
                                }
                            }
                        } else if (isDraggingDown) {
                            change.consume()
                            val newTarget = (offsetY.value + dy).coerceAtLeast(0f)
                            coroutineScope.launch {
                                offsetY.snapTo(newTarget)
                            }
                        } else if (isDraggingUp) {
                            // Upward drag: consume pointer movement without translating image
                            change.consume()
                        }
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = offsetY.value.coerceAtLeast(0f)
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            content()
        }
    }
}
