package com.gallery.feature.editor.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.net.Uri
import android.os.Build
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CropRotate
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.gallery.feature.editor.viewmodel.ColorFilter
import com.gallery.feature.editor.viewmodel.ColorMatrixRgbEffect
import com.gallery.feature.editor.viewmodel.MIN_VIDEO_CLIP_DURATION_MS
import com.gallery.feature.editor.viewmodel.VideoEditorTab
import com.gallery.feature.editor.viewmodel.VideoEditorViewModel
import com.gallery.feature.editor.viewmodel.buildVideoColorMatrix
import com.gallery.feature.editor.viewmodel.toOpenGl4x4Matrix
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.math.roundToInt

private val SPEED_PRESETS = listOf(0.5f, 1.0f, 1.5f, 2.0f)

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen(
    mediaUriString: String,
    onBack: () -> Unit,
    viewModel: VideoEditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val sourceUri = remember(mediaUriString) { Uri.parse(mediaUriString) }

    LaunchedEffect(sourceUri) {
        viewModel.loadVideo(sourceUri)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissError()
        }
    }

    // Intercept Back press while exporting so user cancels cleanly instead of stranding export
    BackHandler(enabled = uiState.isExporting) {
        viewModel.cancelExport()
    }

    // Dedicated ExoPlayer for real-time trimmed/filtered preview
    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    // Attach media item when source URI loads
    LaunchedEffect(sourceUri) {
        exoPlayer.setMediaItem(ExoMediaItem.fromUri(sourceUri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Observe player duration fallback & playback state
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val dur = exoPlayer.duration
                    if (dur > 0L) {
                        viewModel.onPlayerDurationKnown(dur)
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Sync play/pause, speed, and mute state to ExoPlayer
    LaunchedEffect(uiState.isPlaying) {
        exoPlayer.playWhenReady = uiState.isPlaying
    }
    LaunchedEffect(uiState.playbackSpeed) {
        exoPlayer.setPlaybackSpeed(uiState.playbackSpeed)
    }
    LaunchedEffect(uiState.isMuted) {
        exoPlayer.volume = if (uiState.isMuted) 0f else 1f
    }

    // Live color filter matrix for preview
    val androidColorMatrix = remember(uiState.activeFilter, uiState.brightness, uiState.saturation) {
        buildVideoColorMatrix(
            filter = uiState.activeFilter,
            brightness = uiState.brightness,
            saturation = uiState.saturation
        )
    }

    // On API 26–30 (before RenderEffect), apply live color effect through ExoPlayer's GL pipeline
    LaunchedEffect(androidColorMatrix, uiState.hasColorEdits) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val effects = if (uiState.hasColorEdits) {
                listOf(ColorMatrixRgbEffect(androidColorMatrix.toOpenGl4x4Matrix()))
            } else {
                emptyList()
            }
            exoPlayer.setVideoEffects(effects)
        }
    }

    // Loop playback strictly within [trimStartMs, trimEndMs] and drive live playhead position
    LaunchedEffect(uiState.isPlaying, uiState.trimStartMs, uiState.trimEndMs, uiState.durationMs) {
        if (uiState.durationMs <= 0L) return@LaunchedEffect
        val trimEnd = uiState.trimEndMs.coerceAtLeast(uiState.trimStartMs + MIN_VIDEO_CLIP_DURATION_MS)
        while (isActive) {
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (pos < uiState.trimStartMs || pos >= trimEnd) {
                exoPlayer.seekTo(uiState.trimStartMs)
                viewModel.updateCurrentPosition(uiState.trimStartMs)
            } else {
                viewModel.updateCurrentPosition(pos)
            }
            if (!uiState.isPlaying) break
            delay(45L)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Video Editor", fontWeight = FontWeight.SemiBold)
                        if (uiState.trimmedDurationMs > 0L) {
                            Text(
                                text = "Clip ${formatVideoTime(uiState.trimmedDurationMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.isExporting) viewModel.cancelExport() else onBack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            viewModel.exportVideo { _ ->
                                Toast.makeText(
                                    context,
                                    "Saved edited video to Gallery",
                                    Toast.LENGTH_SHORT
                                ).show()
                                onBack()
                            }
                        },
                        enabled = !uiState.isExporting && uiState.durationMs > 0L,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.padding(end = 10.dp)
                    ) {
                        Text("Save", fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Live Video Preview Viewport ──────────────────────────────────────────────
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .clickable(enabled = !uiState.isExporting) {
                        viewModel.togglePlayPause()
                    },
                contentAlignment = Alignment.Center
            ) {
                val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val aspect = uiState.videoAspectRatio
                val isQuarterTurn = uiState.rotationDegrees == 90 || uiState.rotationDegrees == 270

                // Compute unrotated video box dimensions inside viewport, then scale cleanly on 90/270° turns
                val unrotatedW: Float
                val unrotatedH: Float
                if (boxW / boxH > aspect) {
                    unrotatedH = boxH
                    unrotatedW = boxH * aspect
                } else {
                    unrotatedW = boxW
                    unrotatedH = boxW / aspect
                }
                val fitScale = if (isQuarterTurn) {
                    min(boxW / unrotatedH, boxH / unrotatedW).coerceIn(0.2f, 2.5f)
                } else {
                    1f
                }

                val composeRenderEffect = remember(androidColorMatrix, uiState.hasColorEdits) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && uiState.hasColorEdits) {
                        RenderEffect.createColorFilterEffect(
                            ColorMatrixColorFilter(androidColorMatrix)
                        ).asComposeRenderEffect()
                    } else {
                        null
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).also { textureView ->
                                exoPlayer.setVideoTextureView(textureView)
                            }
                        },
                        update = { textureView ->
                            exoPlayer.setVideoTextureView(textureView)
                        },
                        modifier = Modifier
                            .aspectRatio(aspect, matchHeightConstraintsFirst = boxW / boxH > aspect)
                            .graphicsLayer {
                                rotationZ = uiState.rotationDegrees.toFloat()
                                scaleX = fitScale * (if (uiState.isFlippedHorizontal) -1f else 1f)
                                scaleY = fitScale
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    renderEffect = composeRenderEffect
                                }
                            }
                    )
                }

                // Centered compact Play/Pause toggle when paused
                if (!uiState.isPlaying && !uiState.isExporting) {
                    Surface(
                        onClick = { viewModel.togglePlayPause() },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                // Bottom-center Play/Pause + Live Position / Trimmed Duration readout pill
                if (!uiState.isExporting && uiState.durationMs > 0L) {
                    val relativePos = (uiState.currentPositionMs - uiState.trimStartMs)
                        .coerceIn(0L, uiState.trimmedDurationMs)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.68f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            IconButton(
                                onClick = { viewModel.togglePlayPause() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${formatVideoTime(relativePos)} / ${formatVideoTime(uiState.trimmedDurationMs)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (uiState.playbackSpeed != 1.0f) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = formatSpeedLabel(uiState.playbackSpeed),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (uiState.isMuted) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.VolumeOff,
                                    contentDescription = "Muted",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // Metadata loading indicator
                if (uiState.isLoadingMetadata) {
                    CircularProgressIndicator(color = Color.White)
                }

                // Real-time Export Progress Overlay with Cancel
                if (uiState.isExporting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.78f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                            modifier = Modifier
                                .fillMaxWidth(0.82f)
                                .padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Exporting Video",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "${uiState.exportProgress}% complete",
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(14.dp))
                                LinearProgressIndicator(
                                    progress = { uiState.exportProgress / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(Modifier.height(16.dp))
                                OutlinedButton(
                                    onClick = viewModel::cancelExport,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

            // ── Active Editing Tool Panel ────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A))
                    .padding(vertical = 12.dp)
            ) {
                when (uiState.selectedTab) {
                    VideoEditorTab.TRIM -> TrimToolPanel(
                        durationMs = uiState.durationMs,
                        trimStartMs = uiState.trimStartMs,
                        trimEndMs = uiState.trimEndMs,
                        currentPositionMs = uiState.currentPositionMs,
                        filmstripFrames = uiState.filmstripFrames,
                        hasTrim = uiState.hasTrim,
                        onTrimStartChanged = { newStart ->
                            viewModel.setPlaying(false)
                            viewModel.updateTrimStart(newStart)
                            exoPlayer.seekTo(newStart)
                        },
                        onTrimEndChanged = { newEnd ->
                            viewModel.setPlaying(false)
                            viewModel.updateTrimEnd(newEnd)
                            exoPlayer.seekTo(newEnd)
                        },
                        onScrubPosition = { posMs ->
                            viewModel.updateCurrentPosition(posMs)
                            exoPlayer.seekTo(posMs)
                        },
                        onResetTrim = {
                            viewModel.resetTrim()
                            exoPlayer.seekTo(0L)
                        }
                    )

                    VideoEditorTab.TRANSFORM -> TransformToolPanel(
                        rotationDegrees = uiState.rotationDegrees,
                        isFlippedHorizontal = uiState.isFlippedHorizontal,
                        onRotateLeft = { viewModel.rotate(-90) },
                        onRotateRight = { viewModel.rotate(90) },
                        onToggleFlip = viewModel::toggleFlipHorizontal
                    )

                    VideoEditorTab.FILTERS -> FiltersToolPanel(
                        activeFilter = uiState.activeFilter,
                        onFilterSelected = viewModel::applyFilter
                    )

                    VideoEditorTab.ADJUST -> AdjustToolPanel(
                        brightness = uiState.brightness,
                        saturation = uiState.saturation,
                        onBrightnessChange = viewModel::adjustBrightness,
                        onSaturationChange = viewModel::adjustSaturation,
                        onReset = viewModel::resetAdjustments
                    )

                    VideoEditorTab.AUDIO_SPEED -> AudioSpeedToolPanel(
                        playbackSpeed = uiState.playbackSpeed,
                        isMuted = uiState.isMuted,
                        onSpeedSelected = viewModel::setPlaybackSpeed,
                        onToggleMute = viewModel::toggleMute
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.14f))

            // ── Bottom Editing Tool Tabs (matching Photo Editor style) ───────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                VideoTabButton(
                    icon = Icons.Outlined.ContentCut,
                    label = VideoEditorTab.TRIM.label,
                    isSelected = uiState.selectedTab == VideoEditorTab.TRIM,
                    onClick = { viewModel.selectTab(VideoEditorTab.TRIM) }
                )
                VideoTabButton(
                    icon = Icons.Outlined.CropRotate,
                    label = VideoEditorTab.TRANSFORM.label,
                    isSelected = uiState.selectedTab == VideoEditorTab.TRANSFORM,
                    onClick = { viewModel.selectTab(VideoEditorTab.TRANSFORM) }
                )
                VideoTabButton(
                    icon = Icons.Outlined.AutoAwesome,
                    label = VideoEditorTab.FILTERS.label,
                    isSelected = uiState.selectedTab == VideoEditorTab.FILTERS,
                    onClick = { viewModel.selectTab(VideoEditorTab.FILTERS) }
                )
                VideoTabButton(
                    icon = Icons.Outlined.Tune,
                    label = VideoEditorTab.ADJUST.label,
                    isSelected = uiState.selectedTab == VideoEditorTab.ADJUST,
                    onClick = { viewModel.selectTab(VideoEditorTab.ADJUST) }
                )
                VideoTabButton(
                    icon = Icons.Outlined.Speed,
                    label = VideoEditorTab.AUDIO_SPEED.label,
                    isSelected = uiState.selectedTab == VideoEditorTab.AUDIO_SPEED,
                    onClick = { viewModel.selectTab(VideoEditorTab.AUDIO_SPEED) }
                )
            }
        }
    }
}

@Composable
private fun TrimToolPanel(
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    currentPositionMs: Long,
    filmstripFrames: List<Bitmap>,
    hasTrim: Boolean,
    onTrimStartChanged: (Long) -> Unit,
    onTrimEndChanged: (Long) -> Unit,
    onScrubPosition: (Long) -> Unit,
    onResetTrim: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TimeBadge(label = "Start", value = formatVideoTime(trimStartMs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeBadge(
                    label = "Selected",
                    value = formatVideoTime((trimEndMs - trimStartMs).coerceAtLeast(0L)),
                    highlight = true
                )
                if (hasTrim) {
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = onResetTrim, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.RestartAlt,
                            contentDescription = "Reset Trim",
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            TimeBadge(label = "End", value = formatVideoTime(trimEndMs))
        }

        Spacer(Modifier.height(10.dp))

        TrimFilmstripBar(
            durationMs = durationMs,
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs,
            currentPositionMs = currentPositionMs,
            filmstripFrames = filmstripFrames,
            onTrimStartChanged = onTrimStartChanged,
            onTrimEndChanged = onTrimEndChanged,
            onScrubPosition = onScrubPosition
        )
    }
}

@Composable
private fun TimeBadge(
    label: String,
    value: String,
    highlight: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 10.sp
        )
        Text(
            text = value,
            color = if (highlight) MaterialTheme.colorScheme.primary else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TrimFilmstripBar(
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    currentPositionMs: Long,
    filmstripFrames: List<Bitmap>,
    onTrimStartChanged: (Long) -> Unit,
    onTrimEndChanged: (Long) -> Unit,
    onScrubPosition: (Long) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val handleWidthDp = 16.dp
    val handleWidthPx = with(density) { handleWidthDp.toPx() }

    val latestDuration by rememberUpdatedState(durationMs)
    val latestStart by rememberUpdatedState(trimStartMs)
    val latestEnd by rememberUpdatedState(trimEndMs)
    val latestOnStart by rememberUpdatedState(onTrimStartChanged)
    val latestOnEnd by rememberUpdatedState(onTrimEndChanged)
    val latestOnScrub by rememberUpdatedState(onScrubPosition)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
    ) {
        val totalWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val trackWidthPx = (totalWidthPx - handleWidthPx * 2f).coerceAtLeast(1f)
        val safeDuration = durationMs.coerceAtLeast(1L)

        val startFraction = (trimStartMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
        val endFraction = (trimEndMs.toFloat() / safeDuration.toFloat()).coerceIn(startFraction, 1f)
        val playheadFraction = (currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(startFraction, endFraction)

        val leftHandleX = startFraction * trackWidthPx
        val rightHandleX = handleWidthPx + endFraction * trackWidthPx
        val playheadX = handleWidthPx + playheadFraction * trackWidthPx

        // Filmstrip thumbnails background
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = handleWidthDp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1C1C1C))
                .pointerInput(safeDuration, trackWidthPx) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                        val targetMs = (fraction * latestDuration)
                            .toLong()
                            .coerceIn(latestStart, latestEnd)
                        latestOnScrub(targetMs)
                    }
                }
        ) {
            if (filmstripFrames.isNotEmpty()) {
                filmstripFrames.forEach { bitmap ->
                    if (!bitmap.isRecycled) {
                        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Loading filmstrip...",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Dimmed left trimmed-out region
        if (startFraction > 0f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(handleWidthPx.roundToInt(), 0) }
                    .width(with(density) { (startFraction * trackWidthPx).toDp() })
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.65f))
            )
        }

        // Dimmed right trimmed-out region
        if (endFraction < 1f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset((handleWidthPx + endFraction * trackWidthPx).roundToInt(), 0) }
                    .width(with(density) { ((1f - endFraction) * trackWidthPx).toDp() })
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.65f))
            )
        }

        // Active clip border frame
        val activeWidthPx = ((endFraction - startFraction) * trackWidthPx).coerceAtLeast(0f)
        Box(
            modifier = Modifier
                .offset { IntOffset((handleWidthPx + startFraction * trackWidthPx).roundToInt(), 0) }
                .width(with(density) { activeWidthPx.toDp() })
                .fillMaxHeight()
                .border(2.dp, MaterialTheme.colorScheme.primary)
        )

        // Live white vertical playhead indicator
        Box(
            modifier = Modifier
                .offset { IntOffset((playheadX - 1.5f).roundToInt(), 0) }
                .width(3.dp)
                .fillMaxHeight()
                .background(Color.White, RoundedCornerShape(1.5.dp))
        )

        // Left Trim Handle
        var leftDragAccumPx by remember { mutableFloatStateOf(0f) }
        var leftLastTickBucket by remember { mutableStateOf(-1) }
        Box(
            modifier = Modifier
                .offset { IntOffset(leftHandleX.roundToInt(), 0) }
                .width(handleWidthDp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(trackWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            leftDragAccumPx = (latestStart.toFloat() / latestDuration.coerceAtLeast(1L)) * trackWidthPx
                            leftLastTickBucket = (latestStart / 250L).toInt()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val dur = latestDuration
                            if (dur <= 0L) return@detectHorizontalDragGestures
                            val maxPx = ((latestEnd - MIN_VIDEO_CLIP_DURATION_MS).coerceAtLeast(0L).toFloat() / dur) * trackWidthPx
                            leftDragAccumPx = (leftDragAccumPx + dragAmount).coerceIn(0f, maxPx)
                            val newStart = ((leftDragAccumPx / trackWidthPx) * dur).toLong()
                            val bucket = (newStart / 250L).toInt()
                            if (bucket != leftLastTickBucket) {
                                leftLastTickBucket = bucket
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            latestOnStart(newStart)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp))
            )
        }

        // Right Trim Handle
        var rightDragAccumPx by remember { mutableFloatStateOf(trackWidthPx) }
        var rightLastTickBucket by remember { mutableStateOf(-1) }
        Box(
            modifier = Modifier
                .offset { IntOffset(rightHandleX.roundToInt(), 0) }
                .width(handleWidthDp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(trackWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            rightDragAccumPx = (latestEnd.toFloat() / latestDuration.coerceAtLeast(1L)) * trackWidthPx
                            rightLastTickBucket = (latestEnd / 250L).toInt()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val dur = latestDuration
                            if (dur <= 0L) return@detectHorizontalDragGestures
                            val minPx = ((latestStart + MIN_VIDEO_CLIP_DURATION_MS).coerceAtMost(dur).toFloat() / dur) * trackWidthPx
                            rightDragAccumPx = (rightDragAccumPx + dragAmount).coerceIn(minPx, trackWidthPx)
                            val newEnd = ((rightDragAccumPx / trackWidthPx) * dur).toLong()
                            val bucket = (newEnd / 250L).toInt()
                            if (bucket != rightLastTickBucket) {
                                rightLastTickBucket = bucket
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            latestOnEnd(newEnd)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun TransformToolPanel(
    rotationDegrees: Int,
    isFlippedHorizontal: Boolean,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onToggleFlip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Rotation: ${rotationDegrees}°" + if (isFlippedHorizontal) "  •  Mirrored" else "",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransformChipButton(
                icon = Icons.AutoMirrored.Outlined.RotateLeft,
                label = "Rotate -90°",
                isActive = false,
                onClick = onRotateLeft
            )
            TransformChipButton(
                icon = Icons.AutoMirrored.Outlined.RotateRight,
                label = "Rotate +90°",
                isActive = rotationDegrees != 0,
                onClick = onRotateRight
            )
            TransformChipButton(
                icon = Icons.Outlined.Flip,
                label = "Flip",
                isActive = isFlippedHorizontal,
                onClick = onToggleFlip
            )
        }
    }
}

@Composable
private fun TransformChipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
        border = if (isActive) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) MaterialTheme.colorScheme.onPrimary else Color.White,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = if (isActive) MaterialTheme.colorScheme.onPrimary else Color.White,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun FiltersToolPanel(
    activeFilter: ColorFilter,
    onFilterSelected: (ColorFilter) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Color Filters",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(ColorFilter.entries) { filter ->
                FilterChipItem(
                    label = filter.displayName,
                    isSelected = activeFilter == filter,
                    onClick = { onFilterSelected(filter) }
                )
            }
        }
    }
}

@Composable
private fun AdjustToolPanel(
    brightness: Float,
    saturation: Float,
    onBrightnessChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Brightness  ${(brightness * 100).roundToInt()}%",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            if (brightness != 1.0f || saturation != 1.0f) {
                TextButton(
                    onClick = onReset,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Reset", fontSize = 12.sp)
                }
            }
        }
        Slider(
            value = brightness,
            onValueChange = onBrightnessChange,
            valueRange = 0.5f..1.5f,
            modifier = Modifier.height(32.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Saturation  ${(saturation * 100).roundToInt()}%",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Slider(
            value = saturation,
            onValueChange = onSaturationChange,
            valueRange = 0f..2.0f,
            modifier = Modifier.height(32.dp)
        )
    }
}

@Composable
private fun AudioSpeedToolPanel(
    playbackSpeed: Float,
    isMuted: Boolean,
    onSpeedSelected: (Float) -> Unit,
    onToggleMute: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Audio Track",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Surface(
                onClick = onToggleMute,
                shape = RoundedCornerShape(10.dp),
                color = if (isMuted) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.1f),
                border = if (isMuted) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Icon(
                        imageVector = if (isMuted) {
                            Icons.AutoMirrored.Outlined.VolumeOff
                        } else {
                            Icons.AutoMirrored.Outlined.VolumeUp
                        },
                        contentDescription = if (isMuted) "Unmute Audio" else "Mute Audio",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isMuted) "Audio Muted" else "Mute Audio",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Speed",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEED_PRESETS.forEach { speed ->
                    FilterChipItem(
                        label = formatSpeedLabel(speed),
                        isSelected = playbackSpeed == speed,
                        onClick = { onSpeedSelected(speed) }
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoTabButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.75f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

private fun formatVideoTime(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatSpeedLabel(speed: Float): String = when (speed) {
    0.5f -> "0.5x"
    1.0f -> "1x"
    1.5f -> "1.5x"
    2.0f -> "2x"
    else -> "${speed}x"
}
