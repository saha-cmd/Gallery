package com.gallery.feature.viewer.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.gallery.core.data.permissions.MediaPermissionManager
import com.gallery.core.domain.model.MediaItem
import com.gallery.core.ui.components.AddToAlbumDialog
import com.gallery.core.ui.components.DirectVaultTransferPermissionDialog
import com.gallery.core.ui.components.MediaInfoBottomSheet
import com.gallery.core.ui.components.MoveToAlbumDialog
import com.gallery.core.ui.components.MoveToVaultAlbumDialog
import com.gallery.core.ui.components.SleekVideoScrubber
import com.gallery.core.ui.components.SwipeToDismissBox
import com.gallery.feature.viewer.R
import com.gallery.feature.viewer.viewmodel.ViewerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.absoluteValue

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    mediaId: Long,
    isVideo: Boolean,
    onBack: () -> Unit,
    onEdit: (String, Boolean) -> Unit,
    albumId: Long = -1L,
    viewModel: ViewerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val vaultAlbums by viewModel.vaultAlbums.collectAsStateWithLifecycle()
    val customAlbums by viewModel.customAlbums.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val isXdrActive by viewModel.isXdrActive.collectAsStateWithLifecycle()
    val isAutoHideControlsEnabled by viewModel.isAutoHideControlsEnabled.collectAsStateWithLifecycle()
    var currentPhotoHasGainmap by remember { mutableStateOf(false) }

    val mediaList = uiState.mediaList
    var isCurrentMediaZoomed by remember { mutableStateOf(false) }

    // Intercept Back button so back press directly closes the viewer
    BackHandler {
        onBack()
    }

    // Configure Window HDR color mode and dynamic headroom according to XDR setting
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(isXdrActive) {
        val window = activity?.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && window != null) {
            val originalColorMode = window.colorMode
            if (isXdrActive) {
                window.colorMode = ActivityInfo.COLOR_MODE_HDR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    try {
                        window.desiredHdrHeadroom = 0f
                    } catch (_: Throwable) {}
                }
            } else {
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    try {
                        window.desiredHdrHeadroom = 1.0f
                    } catch (_: Throwable) {}
                }
            }
            onDispose {
                window.colorMode = originalColorMode
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    try {
                        window.desiredHdrHeadroom = 1.0f
                    } catch (_: Throwable) {}
                }
            }
        } else {
            onDispose { }
        }
    }

    var areControlsVisible by remember { mutableStateOf(true) }
    var isScrubbing by remember { mutableStateOf(false) }
    var isMoreMenuExpanded by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<MediaItem?>(null) }
    var itemToMoveToVault by remember { mutableStateOf<MediaItem?>(null) }
    var itemToAddToAlbum by remember { mutableStateOf<MediaItem?>(null) }
    var itemToMoveToAlbum by remember { mutableStateOf<MediaItem?>(null) }

    // Close overflow menu if controls are toggled off
    LaunchedEffect(areControlsVisible) {
        if (!areControlsVisible) {
            isMoreMenuExpanded = false
        }
    }

    // System delete launcher for Scoped Storage consent
    val systemDeleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVaultDeleteConfirmed()
        } else {
            viewModel.onVaultDeleteCancelled()
        }
    }

    val activeVaultDeleteSender = uiState.pendingVaultDeleteIntentSender ?: uiState.pendingDeleteIntentSender
    LaunchedEffect(activeVaultDeleteSender, uiState.showDirectTransferPermissionDialog) {
        if (!uiState.showDirectTransferPermissionDialog) {
            activeVaultDeleteSender?.let { sender ->
                systemDeleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                viewModel.clearPendingDeleteIntent()
            }
        }
    }

    val manageFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (MediaPermissionManager.canModifyMediaSilently(context)) {
            viewModel.dismissDirectTransferPermissionDialog()
            viewModel.onVaultDeleteConfirmed()
        }
    }

    if (uiState.showDirectTransferPermissionDialog) {
        DirectVaultTransferPermissionDialog(
            onDismissRequest = {
                viewModel.dismissDirectTransferPermissionDialog()
                viewModel.onVaultDeleteCancelled()
            },
            onAllowDirectTransfer = {
                viewModel.dismissDirectTransferPermissionDialog()
                manageFilesAccessLauncher.launch(
                    MediaPermissionManager.createManageAllFilesAccessIntent(context)
                )
            },
            onContinueWithSystemPopup = {
                viewModel.dismissDirectTransferPermissionDialog()
                val sender = uiState.pendingVaultDeleteIntentSender ?: uiState.pendingDeleteIntentSender
                sender?.let {
                    systemDeleteLauncher.launch(IntentSenderRequest.Builder(it).build())
                    viewModel.clearPendingDeleteIntent()
                }
            }
        )
    }

    // Scoped Storage move permission launcher
    val movePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.retryPendingMove()
        } else {
            viewModel.clearPendingMove()
        }
    }

    LaunchedEffect(uiState.pendingMoveIntentSender) {
        uiState.pendingMoveIntentSender?.let { sender ->
            movePermissionLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    // If item was moved out of the current album and the album is now empty, return to gallery
    LaunchedEffect(uiState.itemMovedOut) {
        if (uiState.itemMovedOut) {
            onBack()
        }
    }

    // If item was moved to vault, return to gallery
    LaunchedEffect(uiState.itemMovedToVault) {
        if (uiState.itemMovedToVault) {
            onBack()
        }
    }

    // Automatically return to gallery if media list becomes empty after loading (e.g. after deleting sole item)
    LaunchedEffect(uiState.isLoading, mediaList.isEmpty()) {
        if (!uiState.isLoading && mediaList.isEmpty()) {
            onBack()
        }
    }

    // Auto-hide controls after 3.5s (paused while user is actively scrubbing the timeline, more menu is open, move dialog is open, or disabled in settings)
    val isMoveToAlbumDialogOpen = itemToMoveToAlbum != null
    LaunchedEffect(areControlsVisible, isScrubbing, isMoreMenuExpanded, isAutoHideControlsEnabled, isMoveToAlbumDialogOpen) {
        if (areControlsVisible && !isScrubbing && !isMoreMenuExpanded && !isMoveToAlbumDialogOpen && isAutoHideControlsEnabled) {
            delay(3500)
            areControlsVisible = false
        }
    }

    SwipeToDismissBox(
        onDismiss = onBack,
        enabled = !isCurrentMediaZoomed,
        onSwipeUp = { showInfoSheet = true }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            } else if (mediaList.isNotEmpty()) {
                val initialIndex = remember(mediaId) {
                    mediaList.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
                }
                val pagerState = rememberPagerState(initialPage = initialIndex) { mediaList.size }

                val currentItem = mediaList.getOrNull(pagerState.currentPage)
                    ?: mediaList.getOrNull(mediaList.lastIndex)

                // Clamp pager cleanly to the new lastIndex if the final item was deleted
                LaunchedEffect(mediaList.size) {
                    if (mediaList.isNotEmpty() && pagerState.currentPage >= mediaList.size) {
                        pagerState.scrollToPage(mediaList.lastIndex)
                    }
                }

                // Reset zoom, XDR preview override, and more menu when navigating between pages or when item shifts
                LaunchedEffect(currentItem?.id) {
                    isCurrentMediaZoomed = false
                    currentPhotoHasGainmap = false
                    isMoreMenuExpanded = false
                    viewModel.resetXdrPreview()
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    pageSpacing = 16.dp,
                    key = { mediaList.getOrNull(it)?.id ?: it },
                    userScrollEnabled = !isCurrentMediaZoomed,
                    beyondViewportPageCount = 1
                ) { page ->
                    val item = mediaList.getOrNull(page) ?: return@HorizontalPager
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                                val clamped = pageOffset.coerceIn(0f, 1f)
                                val scale = lerp(1f, 0.92f, clamped)
                                val alpha = lerp(1f, 0.5f, clamped)
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                            }
                    ) {
                        when (item) {
                            is MediaItem.Photo -> ZoomablePhotoViewer(
                                item = item,
                                isXdrActive = isXdrActive,
                                onTap = { areControlsVisible = !areControlsVisible },
                                onZoomChanged = { zoomed ->
                                    if (page == pagerState.currentPage) {
                                        isCurrentMediaZoomed = zoomed
                                    }
                                },
                                onGainmapDetected = { hasGainmap ->
                                    if (page == pagerState.currentPage) {
                                        currentPhotoHasGainmap = hasGainmap
                                    }
                                }
                            )
                            is MediaItem.Video -> VideoPlayer(
                                item = item,
                                isActivePage = page == pagerState.currentPage,
                                areControlsVisible = areControlsVisible,
                                onTap = { areControlsVisible = !areControlsVisible },
                                onScrubbingChanged = { isScrubbing = it },
                                onVideoEnded = { areControlsVisible = true },
                                onZoomChanged = { zoomed ->
                                    if (page == pagerState.currentPage) {
                                        isCurrentMediaZoomed = zoomed
                                    }
                                }
                            )
                        }
                    }
                }

                // Controls overlay
                AnimatedVisibility(
                    visible = areControlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ViewerControlsOverlay(
                        currentItem = currentItem,
                        hasGainmap = currentPhotoHasGainmap,
                        isXdrActive = isXdrActive,
                        isMoreMenuExpanded = isMoreMenuExpanded,
                        onMoreMenuExpandedChange = { isMoreMenuExpanded = it },
                        onToggleXdr = { viewModel.toggleXdrPreview() },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onAddToAlbum = { itemToAddToAlbum = it },
                        onMoveToAlbum = { itemToMoveToAlbum = it },
                        onBack = onBack,
                        onEdit = { item ->
                            onEdit(item.uri.toString(), item is MediaItem.Video)
                        },
                        onShare = { item ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_STREAM, item.uri)
                                type = if (item is MediaItem.Photo) item.mimeType else "video/*"
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share ${item.name}"))
                        },
                        onDelete = { item ->
                            itemToDelete = item
                        },
                        onMoveToVault = { item ->
                            itemToMoveToVault = item
                        },
                        onShowInfo = {
                            showInfoSheet = true
                        }
                    )
                }

                // Info Bottom Sheet (triggered via swipe up or Info button)
                if (showInfoSheet) {
                    currentItem?.let { item ->
                        MediaInfoBottomSheet(
                            item = item,
                            onDismissRequest = { showInfoSheet = false }
                        )
                    }
                }
            }

            // Feedback Snackbar
            uiState.infoMessage?.let { msg ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::dismissInfoMessage) { Text("OK") }
                    }
                ) { Text(msg) }
            }
        }
    }

    // Move to Vault Album Dialog
    itemToMoveToVault?.let { item ->
        MoveToVaultAlbumDialog(
            itemCount = 1,
            existingAlbums = vaultAlbums,
            onDismiss = { itemToMoveToVault = null },
            onConfirm = { albumName ->
                viewModel.moveToVault(item, albumName)
                itemToMoveToVault = null
            }
        )
    }

    // Add to Custom Album Dialog
    itemToAddToAlbum?.let { item ->
        AddToAlbumDialog(
            customAlbums = customAlbums,
            onDismissRequest = { itemToAddToAlbum = null },
            onSelectAlbum = { albumId ->
                viewModel.addToCustomAlbum(albumId, item)
                itemToAddToAlbum = null
            },
            onCreateNewAlbum = { albumName ->
                viewModel.createAndAddToCustomAlbum(albumName, item)
                itemToAddToAlbum = null
            },
            itemCount = 1
        )
    }

    // Move to Album Dialog
    itemToMoveToAlbum?.let { item ->
        MoveToAlbumDialog(
            itemCount = 1,
            albums = albums,
            currentAlbumId = if (albumId != -1L && albumId != -2L) albumId else null,
            onDismiss = { itemToMoveToAlbum = null },
            onConfirm = { targetAlbum, newAlbumName ->
                viewModel.moveToAlbum(item, targetAlbum, newAlbumName)
                itemToMoveToAlbum = null
            }
        )
    }

    // Delete confirmation dialog
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            icon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Move to Recycle Bin?") },
            text = { Text("\"${item.name}\" will be moved to the Recycle Bin. You can restore it back to the main gallery anytime.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteItem(item)
                        itemToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Move to Bin")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ZoomablePhotoViewer(
    item: MediaItem.Photo,
    isXdrActive: Boolean = true,
    onTap: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {},
    onGainmapDetected: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(item.id) { mutableStateOf(IntSize.Zero) }
    val coroutineScope = rememberCoroutineScope()
    var animationJob by remember(item.id) { mutableStateOf<Job?>(null) }

    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)

    fun clampOffset(target: Offset, targetScale: Float, size: IntSize): Offset {
        if (targetScale <= 1f || size.width == 0 || size.height == 0) return Offset.Zero
        val maxX = size.width * (targetScale - 1f) / 2f
        val maxY = size.height * (targetScale - 1f) / 2f
        return Offset(
            x = target.x.coerceIn(-maxX, maxX),
            y = target.y.coerceIn(-maxY, maxY)
        )
    }

    fun animateTransformTo(targetScale: Float, targetOffset: Offset, zoomed: Boolean) {
        animationJob?.cancel()
        currentOnZoomChanged(zoomed)
        val startScale = scale
        val startOffset = offset
        animationJob = coroutineScope.launch {
            val progress = Animatable(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) {
                scale = lerp(startScale, targetScale, value)
                offset = Offset(
                    x = lerp(startOffset.x, targetOffset.x, value),
                    y = lerp(startOffset.y, targetOffset.y, value)
                )
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { newSize ->
                containerSize = newSize
                if (scale > 1f) {
                    offset = clampOffset(offset, scale, newSize)
                }
            }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.02f) {
                            animateTransformTo(
                                targetScale = 1f,
                                targetOffset = Offset.Zero,
                                zoomed = false
                            )
                        } else {
                            val targetScale = 2.5f
                            val containerCenter = Offset(
                                containerSize.width / 2f,
                                containerSize.height / 2f
                            )
                            val rawTargetOffset = (containerCenter - tapOffset) * (targetScale - 1f)
                            val clampedTargetOffset = clampOffset(
                                target = rawTargetOffset,
                                targetScale = targetScale,
                                size = containerSize
                            )
                            animateTransformTo(
                                targetScale = targetScale,
                                targetOffset = clampedTargetOffset,
                                zoomed = true
                            )
                        }
                    }
                )
            }
            .pointerInput(item.id) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var wasMultiTouch = false
                    var pastPanSlop = false
                    var panAccumulated = Offset.Zero

                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }

                        if (pressedCount >= 2) {
                            wasMultiTouch = true
                            animationJob?.cancel()

                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)

                            val oldScale = scale
                            val newScale = (oldScale * zoomChange).coerceIn(0.85f, 5f)
                            val scaleRatio = if (oldScale > 0f) newScale / oldScale else 1f

                            val newRawOffset = if (centroid != Offset.Unspecified) {
                                val containerCenter = Offset(
                                    containerSize.width / 2f,
                                    containerSize.height / 2f
                                )
                                val focalOffset = centroid - containerCenter
                                offset + panChange + (offset - focalOffset) * (scaleRatio - 1f)
                            } else {
                                offset + panChange
                            }

                            scale = newScale
                            offset = clampOffset(newRawOffset, newScale, containerSize)
                            currentOnZoomChanged(newScale > 1.02f)

                            event.changes.forEach { it.consume() }
                        } else if (pressedCount == 1) {
                            if (scale > 1.02f) {
                                val panChange = event.calculatePan()
                                if (!pastPanSlop && !wasMultiTouch) {
                                    panAccumulated += panChange
                                    if (panAccumulated.getDistance() > touchSlop) {
                                        pastPanSlop = true
                                        animationJob?.cancel()
                                        offset = clampOffset(offset + panChange, scale, containerSize)
                                        event.changes.forEach { it.consume() }
                                    }
                                } else {
                                    animationJob?.cancel()
                                    offset = clampOffset(offset + panChange, scale, containerSize)
                                    event.changes.forEach { it.consume() }
                                }
                            } else if (wasMultiTouch) {
                                // Keep consuming trailing 1-finger movement after pinch-out so pager/dismiss don't trigger
                                event.changes.forEach { it.consume() }
                            }
                        } else if (wasMultiTouch || pastPanSlop) {
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (wasMultiTouch && scale <= 1.03f) {
                        animateTransformTo(
                            targetScale = 1f,
                            targetOffset = Offset.Zero,
                            zoomed = false
                        )
                    }
                }
            }
    ) {
        val context = LocalContext.current
        val fullResRequest = remember(item.id, item.uri, item.filePath) {
            ImageRequest.Builder(context)
                .data(item.filePath?.let { File(it) }?.takeIf { it.canRead() } ?: item.uri)
                .size(coil.size.Size.ORIGINAL)
                .precision(coil.size.Precision.EXACT)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .allowHardware(false)
                .placeholderMemoryCacheKey("thumb_${item.id}")
                .memoryCacheKey("orig_${item.id}")
                .diskCacheKey("orig_${item.id}")
                .crossfade(200)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .listener(
                    onSuccess = { _, result ->
                        val drawable = result.drawable
                        if (drawable is BitmapDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            onGainmapDetected(drawable.bitmap.hasGainmap())
                        }
                    }
                )
                .build()
        }

        AsyncImage(
            model = fullResRequest,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

private fun formatTime(ms: Float): String {
    val totalSeconds = (ms / 1000f).toLong().coerceAtLeast(0L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Composable
fun VideoPlayer(
    item: MediaItem.Video,
    isActivePage: Boolean = true,
    areControlsVisible: Boolean = true,
    onTap: () -> Unit = {},
    onScrubbingChanged: (Boolean) -> Unit = {},
    onVideoEnded: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isMuted by remember(item.id) { mutableStateOf(false) }

    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(item.id) { mutableStateOf(IntSize.Zero) }
    val coroutineScope = rememberCoroutineScope()
    var animationJob by remember(item.id) { mutableStateOf<Job?>(null) }

    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)

    fun clampOffset(target: Offset, targetScale: Float, size: IntSize): Offset {
        if (targetScale <= 1f || size.width == 0 || size.height == 0) return Offset.Zero
        val maxX = size.width * (targetScale - 1f) / 2f
        val maxY = size.height * (targetScale - 1f) / 2f
        return Offset(
            x = target.x.coerceIn(-maxX, maxX),
            y = target.y.coerceIn(-maxY, maxY)
        )
    }

    fun animateTransformTo(targetScale: Float, targetOffset: Offset, zoomed: Boolean) {
        animationJob?.cancel()
        currentOnZoomChanged(zoomed)
        val startScale = scale
        val startOffset = offset
        animationJob = coroutineScope.launch {
            val progress = Animatable(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) {
                scale = lerp(startScale, targetScale, value)
                offset = Offset(
                    x = lerp(startOffset.x, targetOffset.x, value),
                    y = lerp(startOffset.y, targetOffset.y, value)
                )
            }
        }
    }

    LaunchedEffect(item.id) {
        animationJob?.cancel()
        scale = 1f
        offset = Offset.Zero
        currentOnZoomChanged(false)
    }

    val exoPlayer = remember(item.uri, item.filePath) {
        val mediaUri = item.filePath?.let { path ->
            val f = java.io.File(path)
            if (f.exists() && f.canRead()) android.net.Uri.fromFile(f) else null
        } ?: item.uri

        val extractorsFactory = DefaultExtractorsFactory().apply {
            setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build().apply {
                setMediaItem(ExoMediaItem.fromUri(mediaUri))
                volume = if (isMuted) 0f else 1f
                prepare()
                playWhenReady = isActivePage
            }
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    var isPlaying by remember(item.id) { mutableStateOf(exoPlayer.isPlaying) }
    var playbackState by remember(item.id) { mutableIntStateOf(exoPlayer.playbackState) }
    var currentPositionMs by remember(item.id) { mutableFloatStateOf(0f) }
    var durationMs by remember(item.id) {
        mutableFloatStateOf(if (item.duration > 0L) item.duration.toFloat() else 0f)
    }
    var isScrubbing by remember(item.id) { mutableStateOf(false) }
    var isSeeking by remember(item.id) { mutableStateOf(false) }
    var scrubPositionMs by remember(item.id) { mutableFloatStateOf(0f) }
    var lastSeekTimestamp by remember(item.id) { mutableLongStateOf(0L) }

    val performSeek: (Long) -> Unit = { targetMs ->
        val maxDuration = if (durationMs > 0f) durationMs.toLong() else Long.MAX_VALUE
        val clamped = targetMs.coerceIn(0L, maxDuration)
        isSeeking = true
        lastSeekTimestamp = System.currentTimeMillis()
        currentPositionMs = clamped.toFloat()
        scrubPositionMs = clamped.toFloat()
        exoPlayer.seekTo(exoPlayer.currentMediaItemIndex, clamped)
        if (playbackState == Player.STATE_ENDED) {
            exoPlayer.play()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                val d = exoPlayer.duration
                if (d > 0) durationMs = d.toFloat()
                if (state == Player.STATE_READY) {
                    isSeeking = false
                    val pos = exoPlayer.currentPosition.toFloat().coerceAtLeast(0f)
                    currentPositionMs = pos
                }
                if (state == Player.STATE_ENDED) {
                    onVideoEnded()
                }
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    isSeeking = false
                    val pos = newPosition.positionMs.toFloat().coerceAtLeast(0f)
                    currentPositionMs = pos
                    val d = exoPlayer.duration
                    if (d > 0) durationMs = d.toFloat()
                }
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val d = exoPlayer.duration
                if (d > 0) durationMs = d.toFloat()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Sync player play/pause state and reset zoom when page is swiped into or out of focus
    LaunchedEffect(isActivePage) {
        if (isActivePage) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
            animationJob?.cancel()
            scale = 1f
            offset = Offset.Zero
            currentOnZoomChanged(false)
        }
    }

    // Periodically sync position while playing (with guard against stale/zero position after seek)
    LaunchedEffect(isPlaying, isScrubbing, isSeeking) {
        while (isActive) {
            val timeSinceSeek = System.currentTimeMillis() - lastSeekTimestamp
            if (!isScrubbing && !isSeeking && timeSinceSeek > 800L) {
                val pos = exoPlayer.currentPosition.toFloat().coerceAtLeast(0f)
                if (pos >= 0f) {
                    // Guard against transient zero-position reporting while decoder buffers keyframes
                    if (!(currentPositionMs > 5000f && pos < 1000f)) {
                        currentPositionMs = pos
                    }
                }
                val d = exoPlayer.duration
                if (d > 0) durationMs = d.toFloat()
            }
            delay(200)
        }
    }

    val totalDuration = if (durationMs > 0f) durationMs else 1f
    val displayPos = if (isScrubbing) scrubPositionMs else currentPositionMs
    val sliderValue = displayPos.coerceIn(0f, totalDuration)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.viewer_texture_player_view, null) as PlayerView).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setOnTouchListener { _, _ -> false }
                }
            },
            update = { playerView ->
                if (playerView.player != exoPlayer) {
                    playerView.player = exoPlayer
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )

        // Overlay transparent Box to capture pinch-to-zoom, pan, single-tap (toggle controls), and double-tap (1x/2.5x zoom)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { newSize ->
                    containerSize = newSize
                    if (scale > 1f) {
                        offset = clampOffset(offset, scale, newSize)
                    }
                }
                .pointerInput(item.id) {
                    detectTapGestures(
                        onTap = { currentOnTap() },
                        onDoubleTap = { tapOffset ->
                            if (scale > 1.02f) {
                                animateTransformTo(
                                    targetScale = 1f,
                                    targetOffset = Offset.Zero,
                                    zoomed = false
                                )
                            } else {
                                val targetScale = 2.5f
                                val containerCenter = Offset(
                                    containerSize.width / 2f,
                                    containerSize.height / 2f
                                )
                                val rawTargetOffset = (containerCenter - tapOffset) * (targetScale - 1f)
                                val clampedTargetOffset = clampOffset(
                                    target = rawTargetOffset,
                                    targetScale = targetScale,
                                    size = containerSize
                                )
                                animateTransformTo(
                                    targetScale = targetScale,
                                    targetOffset = clampedTargetOffset,
                                    zoomed = true
                                )
                            }
                        }
                    )
                }
                .pointerInput(item.id) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var wasMultiTouch = false
                        var pastPanSlop = false
                        var panAccumulated = Offset.Zero

                        do {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }

                            if (pressedCount >= 2) {
                                wasMultiTouch = true
                                animationJob?.cancel()

                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = false)

                                val oldScale = scale
                                val newScale = (oldScale * zoomChange).coerceIn(0.85f, 5f)
                                val scaleRatio = if (oldScale > 0f) newScale / oldScale else 1f

                                val newRawOffset = if (centroid != Offset.Unspecified) {
                                    val containerCenter = Offset(
                                        containerSize.width / 2f,
                                        containerSize.height / 2f
                                    )
                                    val focalOffset = centroid - containerCenter
                                    offset + panChange + (offset - focalOffset) * (scaleRatio - 1f)
                                } else {
                                    offset + panChange
                                }

                                scale = newScale
                                offset = clampOffset(newRawOffset, newScale, containerSize)
                                currentOnZoomChanged(newScale > 1.02f)

                                event.changes.forEach { it.consume() }
                            } else if (pressedCount == 1) {
                                if (scale > 1.02f) {
                                    val panChange = event.calculatePan()
                                    if (!pastPanSlop && !wasMultiTouch) {
                                        panAccumulated += panChange
                                        if (panAccumulated.getDistance() > touchSlop) {
                                            pastPanSlop = true
                                            animationJob?.cancel()
                                            offset = clampOffset(offset + panChange, scale, containerSize)
                                            event.changes.forEach { it.consume() }
                                        }
                                    } else {
                                        animationJob?.cancel()
                                        offset = clampOffset(offset + panChange, scale, containerSize)
                                        event.changes.forEach { it.consume() }
                                    }
                                } else if (wasMultiTouch) {
                                    // Keep consuming trailing 1-finger movement after pinch-out so pager/dismiss don't trigger
                                    event.changes.forEach { it.consume() }
                                }
                            } else if (wasMultiTouch || pastPanSlop) {
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })

                        if (wasMultiTouch && scale <= 1.03f) {
                            animateTransformTo(
                                targetScale = 1f,
                                targetOffset = Offset.Zero,
                                zoomed = false
                            )
                        }
                    }
                }
        )

        // Center Play / Rewind / Fast-Forward controls when paused and controls are visible
        if (areControlsVisible && !isPlaying) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                IconButton(
                    onClick = { performSeek((currentPositionMs - 10_000f).toLong()) },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Replay10,
                        contentDescription = "Rewind 10s",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        .clickable {
                            if (playbackState == Player.STATE_ENDED) {
                                performSeek(0L)
                            }
                            exoPlayer.play()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (playbackState == Player.STATE_ENDED) Icons.Filled.Replay else Icons.Filled.PlayArrow,
                        contentDescription = if (playbackState == Player.STATE_ENDED) "Replay" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                IconButton(
                    onClick = { performSeek((currentPositionMs + 10_000f).toLong()) },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forward10,
                        contentDescription = "Forward 10s",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Full Interactive Timeline Bar (visible when controls are shown)
        if (areControlsVisible) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 96.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures { /* consume tap inside timeline bar so controls don't close */ }
                    },
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.70f),
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rewind 10s
                    IconButton(
                        onClick = { performSeek((currentPositionMs - 10_000f).toLong()) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Replay10,
                            contentDescription = "Rewind 10s",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Play / Pause / Replay Button
                    IconButton(
                        onClick = {
                            if (playbackState == Player.STATE_ENDED) {
                                performSeek(0L)
                                exoPlayer.play()
                            } else if (isPlaying) {
                                exoPlayer.pause()
                            } else {
                                exoPlayer.play()
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                playbackState == Player.STATE_ENDED -> Icons.Filled.Replay
                                isPlaying -> Icons.Filled.Pause
                                else -> Icons.Filled.PlayArrow
                            },
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Forward 10s
                    IconButton(
                        onClick = { performSeek((currentPositionMs + 10_000f).toLong()) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Forward10,
                            contentDescription = "Forward 10s",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Elapsed Time
                    Text(
                        text = formatTime(displayPos),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 2.dp, end = 4.dp)
                    )

                    // Sleek Video Scrubber (allows dragging or tapping anywhere to seek)
                    SleekVideoScrubber(
                        value = sliderValue,
                        onValueChange = { newPos ->
                            isScrubbing = true
                            onScrubbingChanged(true)
                            scrubPositionMs = newPos
                        },
                        onValueChangeFinished = {
                            performSeek(scrubPositionMs.toLong())
                            isScrubbing = false
                            onScrubbingChanged(false)
                        },
                        valueRange = 0f..totalDuration,
                        enabled = durationMs > 0f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        onScrubbingChanged = onScrubbingChanged
                    )

                    // Total Duration
                    Text(
                        text = formatTime(durationMs),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp)
                    )

                    // Speaker Mute / Unmute Toggle Button
                    IconButton(
                        onClick = {
                            val newMuted = !isMuted
                            isMuted = newMuted
                            exoPlayer.volume = if (newMuted) 0f else 1f
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        } else if (durationMs > 0f) {
            // Subtle, sleek progress line at the bottom when controls are hidden
            val progress = (currentPositionMs / durationMs).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerControlsOverlay(
    currentItem: MediaItem?,
    hasGainmap: Boolean,
    isXdrActive: Boolean,
    isMoreMenuExpanded: Boolean,
    onMoreMenuExpandedChange: (Boolean) -> Unit,
    onToggleXdr: () -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onAddToAlbum: (MediaItem) -> Unit,
    onMoveToAlbum: (MediaItem) -> Unit = {},
    onBack: () -> Unit,
    onEdit: (MediaItem) -> Unit,
    onShare: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
    onMoveToVault: (MediaItem) -> Unit,
    onShowInfo: () -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top Bar Scrim and Controls
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Transparent
                        )
                    )
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Top-Left: Back button
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                // Center: Open space so top edge of photo stays completely clear
                Spacer(Modifier.weight(1f))

                // Top-Right: Optional HDR/XDR pill, Favorite button, and Overflow menu
                if (currentItem is MediaItem.Photo && hasGainmap) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isXdrActive) Color(0xFFFFA000) else Color.White.copy(alpha = 0.22f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onToggleXdr)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.HdrOn,
                                contentDescription = "Toggle XDR Ranging",
                                tint = if (isXdrActive) Color.Black else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isXdrActive) "XDR" else "SDR",
                                color = if (isXdrActive) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }

                currentItem?.let { item ->
                    // Favorite Button
                    IconButton(onClick = { onToggleFavorite(item) }) {
                        Icon(
                            imageVector = if (item.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (item.isFavorite) "Remove from Favorites" else "Add to Favorites",
                            tint = if (item.isFavorite) Color(0xFFFF3B30) else Color.White
                        )
                    }

                    // Overflow Menu Button (⋮)
                    Box {
                        IconButton(onClick = { onMoreMenuExpandedChange(!isMoreMenuExpanded) }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "More options",
                                tint = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = isMoreMenuExpanded,
                            onDismissRequest = { onMoreMenuExpandedChange(false) },
                            containerColor = Color(0xFF2C2C2E),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Add to Album", color = Color.White) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.CreateNewFolder,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                },
                                onClick = {
                                    onMoreMenuExpandedChange(false)
                                    onAddToAlbum(item)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Move to Album", color = Color.White) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.DriveFileMove,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                },
                                onClick = {
                                    onMoreMenuExpandedChange(false)
                                    onMoveToAlbum(item)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Move to Secure Folder", color = Color.White) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                },
                                onClick = {
                                    onMoreMenuExpandedChange(false)
                                    onMoveToVault(item)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Thumb-Friendly Floating Bottom Action Pill with Subtle Gradient Scrim
        currentItem?.let { item ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.60f)
                            )
                        )
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF1C1C1E).copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Share
                        ViewerActionItem(
                            icon = Icons.Outlined.Share,
                            label = "Share",
                            onClick = { onShare(item) }
                        )

                        // 2. Edit (Photos and Videos)
                        ViewerActionItem(
                            icon = Icons.Outlined.Edit,
                            label = "Edit",
                            onClick = { onEdit(item) }
                        )

                        // 3. Info
                        ViewerActionItem(
                            icon = Icons.Outlined.Info,
                            label = "Info",
                            onClick = onShowInfo
                        )

                        // 4. Delete
                        ViewerActionItem(
                            icon = Icons.Outlined.Delete,
                            label = "Delete",
                            onClick = { onDelete(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = tint.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
