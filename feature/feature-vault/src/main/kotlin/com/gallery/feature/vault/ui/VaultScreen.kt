package com.gallery.feature.vault.ui

import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gallery.core.ui.components.SleekVideoScrubber
import com.gallery.feature.vault.viewmodel.VaultAuthState
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
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.gallery.core.domain.model.VaultItem
import com.gallery.core.ui.components.AnimatedPinchGridContainer
import com.gallery.core.ui.components.DuplicateAnalyzerSheet
import com.gallery.core.ui.components.EmptyStateView
import com.gallery.core.ui.components.GridScrollPosition
import com.gallery.core.ui.components.SwipeToDismissBox
import com.gallery.core.ui.components.VaultInfoBottomSheet
import com.gallery.core.ui.components.VerticalFastScrollbar
import com.gallery.core.ui.components.gridThumbnailTargetSize
import com.gallery.core.ui.components.rememberViewportOnlyLazyGridState
import com.gallery.feature.vault.R
import com.gallery.feature.vault.viewmodel.VaultAlbum
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.gallery.feature.vault.viewmodel.VaultTab
import com.gallery.feature.vault.viewmodel.VaultViewModel
import java.io.File
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    onEditItem: (String, Boolean) -> Unit = { _, _ -> },
    viewModel: VaultViewModel = hiltViewModel()
) {
    val vaultState by viewModel.vaultState.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val vaultDuplicateScanState by viewModel.vaultDuplicateScanState.collectAsStateWithLifecycle()
    val gridColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var itemToDelete by remember { mutableStateOf<VaultItem?>(null) }
    var itemInfoToShow by remember { mutableStateOf<VaultItem?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showVaultMenu by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showBiometricInfoDialog by remember { mutableStateOf(false) }
    var showDuplicateAnalyzerSheet by remember { mutableStateOf(false) }

    // Auto-lock vault on background or when auth state resets to idle/unauthenticated
    LaunchedEffect(authState) {
        if (authState !is VaultAuthState.Authenticated) {
            onBack()
        }
    }

    // App background lifecycle observer: auto-lock and purge decrypted temp/memory caches on ON_STOP
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.onAppBackgrounded()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.setLaunchingEditor(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Intercept Back button when multi-item selection mode is active
    BackHandler(enabled = vaultState.isSelectionMode && vaultState.activeViewingItem == null) {
        viewModel.clearSelection()
    }

    // Intercept Back button when full-screen viewer is open
    BackHandler(enabled = vaultState.activeViewingItem != null) {
        viewModel.closeViewer()
    }

    // Intercept Back button when viewing a specific album inside Vault
    BackHandler(enabled = vaultState.selectedAlbumName != null && vaultState.activeViewingItem == null) {
        viewModel.closeAlbum()
    }

    // Block screenshots, screen recording and task snapshots inside Vault
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        viewModel.loadVaultItems()
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            viewModel.onLeaveVaultScreen()
        }
    }

    val isViewingItem = vaultState.activeViewingItem != null
    val inAlbum = vaultState.selectedAlbumName != null
    val displayedItems = if (inAlbum) {
        vaultState.items.filter { it.albumName == vaultState.selectedAlbumName }
    } else {
        vaultState.items
    }

    Scaffold(
        topBar = {
            if (!isViewingItem) {
                if (vaultState.isSelectionMode) {
                    TopAppBar(
                        title = {
                            Text(
                                text = "${vaultState.selectedItemIds.size} selected",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = viewModel::clearSelection) {
                                Icon(Icons.Filled.Close, "Exit selection")
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.selectAll(displayedItems) }) {
                                Icon(
                                    imageVector = Icons.Outlined.SelectAll,
                                    contentDescription = "Select all"
                                )
                            }
                            IconButton(
                                onClick = viewModel::restoreSelectedItems,
                                enabled = vaultState.selectedItemIds.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.LockOpen,
                                    contentDescription = "Take Out of Secure Folder"
                                )
                            }
                            IconButton(
                                onClick = { showBatchDeleteDialog = true },
                                enabled = vaultState.selectedItemIds.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Delete permanently",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = if (inAlbum) vaultState.selectedAlbumName!! else "Secure Vault",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (inAlbum) "${displayedItems.size} items" else "${vaultState.items.size} hidden items",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = if (inAlbum) viewModel::closeAlbum else onBack) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, if (inAlbum) "Back to Vault" else "Back")
                            }
                        },
                        actions = {
                            if (!vaultState.isLocked && !vaultState.isSelectionMode) {
                                IconButton(onClick = {
                                    viewModel.startVaultDuplicateScan()
                                    showDuplicateAnalyzerSheet = true
                                }) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Find Duplicates")
                                }
                            }
                            Box {
                                IconButton(onClick = { showVaultMenu = true }) {
                                    Icon(Icons.Outlined.MoreVert, "Options")
                                }
                                DropdownMenu(
                                    expanded = showVaultMenu,
                                    onDismissRequest = { showVaultMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Find Duplicates") },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                        onClick = {
                                            showVaultMenu = false
                                            viewModel.startVaultDuplicateScan()
                                            showDuplicateAnalyzerSheet = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Change PIN") },
                                        leadingIcon = { Icon(Icons.Outlined.Pin, null) },
                                        onClick = {
                                            showVaultMenu = false
                                            showChangePinDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Fingerprint Info") },
                                        leadingIcon = { Icon(Icons.Outlined.Fingerprint, null) },
                                        onClick = {
                                            showVaultMenu = false
                                            showBiometricInfoDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear Corrupted Items") },
                                        leadingIcon = { Icon(Icons.Outlined.CleaningServices, null) },
                                        onClick = {
                                            showVaultMenu = false
                                            viewModel.clearCorruptedItems()
                                        }
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!isViewingItem) Modifier.padding(paddingValues) else Modifier)
        ) {

            when {
                vaultState.isLoading && vaultState.items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                vaultState.items.isEmpty() -> {
                    EmptyStateView(
                        icon = Icons.Outlined.LockOpen,
                        title = "Vault is Empty",
                        subtitle = "Move sensitive photos and videos here from the gallery to keep them encrypted and hidden from other apps"
                    )
                }
                inAlbum -> {
                    if (displayedItems.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Outlined.FolderOpen,
                            title = "Album is Empty",
                            subtitle = "No items in ${vaultState.selectedAlbumName}"
                        )
                    } else {
                        val albumKey = "vault_album_${vaultState.selectedAlbumName}"
                        val savedScroll = remember(albumKey) { viewModel.getGridScrollPosition(albumKey) }
                        val inAlbumGridState = rememberViewportOnlyLazyGridState(
                            initialFirstVisibleItemIndex = savedScroll.index,
                            initialFirstVisibleItemScrollOffset = savedScroll.offset
                        )

                        LaunchedEffect(displayedItems.isNotEmpty()) {
                            if (displayedItems.isNotEmpty() && savedScroll.index > 0 && inAlbumGridState.firstVisibleItemIndex == 0) {
                                inAlbumGridState.scrollToItem(savedScroll.index, savedScroll.offset)
                            }
                        }

                        val inAlbumPreviewRefs = remember { mutableMapOf<String, Int>() }
                        AnimatedPinchGridContainer(
                            currentColumns = gridColumns,
                            onColumnsChanged = viewModel::setGridColumns,
                            minColumns = 2,
                            maxColumns = 7,
                            gridState = inAlbumGridState,
                            initialFirstVisibleItemIndex = savedScroll.index,
                            initialFirstVisibleItemScrollOffset = savedScroll.offset,
                            onScrollPositionChanged = { idx, off ->
                                viewModel.saveGridScrollPosition(albumKey, idx, off)
                            },
                            modifier = Modifier.fillMaxSize(),
                            overlayContent = { activeGridState, _ ->
                                VerticalFastScrollbar(
                                    gridState = activeGridState,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        ) { layerColumns, layerGridState, isPreviewLayer, tileAspectRatio ->
                            val targetSize = gridThumbnailTargetSize(layerColumns)
                            val isLayerScrolling by remember(layerGridState) {
                                derivedStateOf { layerGridState.isScrollInProgress }
                            }
                            val effectiveScrolling = if (isPreviewLayer) false else isLayerScrolling

                            LazyVerticalGrid(
                                state = layerGridState,
                                columns = GridCells.Fixed(layerColumns),
                                contentPadding = PaddingValues(2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(displayedItems, key = { it.id }) { vaultItem ->
                                    val isSelected = vaultItem.id in vaultState.selectedItemIds
                                    VaultThumbnailCard(
                                        item = vaultItem,
                                        previewFile = vaultState.previewFiles[vaultItem.id],
                                        previewBytes = vaultState.previewBytes[vaultItem.id],
                                        previewBitmap = vaultState.previewBitmaps[vaultItem.id],
                                        isCorrupted = vaultState.corruptedItemIds.contains(vaultItem.id),
                                        isScrolling = effectiveScrolling,
                                        isPreviewLayer = isPreviewLayer,
                                        activeRefCounts = inAlbumPreviewRefs,
                                        targetSize = targetSize,
                                        aspectRatio = tileAspectRatio,
                                        isSelectionMode = vaultState.isSelectionMode,
                                        isSelected = isSelected,
                                        onRequestPreview = { viewModel.requestPreview(vaultItem) },
                                        onReleasePreview = { viewModel.releasePreview(vaultItem.id) },
                                        onClick = {
                                            if (vaultState.isSelectionMode) {
                                                viewModel.toggleSelection(vaultItem.id)
                                            } else {
                                                viewModel.saveGridScrollPosition(albumKey, layerGridState.firstVisibleItemIndex, layerGridState.firstVisibleItemScrollOffset)
                                                viewModel.openViewer(vaultItem, displayedItems)
                                            }
                                        },
                                        onLongClick = {
                                            viewModel.toggleSelection(vaultItem.id)
                                        },
                                        onEdit = {
                                            viewModel.editItem(vaultItem) { file, isVideo ->
                                                onEditItem(Uri.fromFile(file).toString(), isVideo)
                                            }
                                        },
                                        onInfo = { itemInfoToShow = vaultItem },
                                        onRestore = { viewModel.restoreItemToGallery(vaultItem) },
                                        onDelete = { itemToDelete = vaultItem }
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TabRow(
                            selectedTabIndex = vaultState.selectedTab.ordinal,
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Tab(
                                selected = vaultState.selectedTab == VaultTab.ALL_MEDIA,
                                onClick = { viewModel.selectTab(VaultTab.ALL_MEDIA) },
                                text = { Text("All Media") },
                                icon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) }
                            )
                            Tab(
                                selected = vaultState.selectedTab == VaultTab.ALBUMS,
                                onClick = { viewModel.selectTab(VaultTab.ALBUMS) },
                                text = { Text("Albums") },
                                icon = { Icon(Icons.Outlined.FolderSpecial, contentDescription = null) }
                            )
                        }

                        when (vaultState.selectedTab) {
                            VaultTab.ALL_MEDIA -> {
                                val savedScroll = remember { viewModel.getGridScrollPosition("vault_all") }
                                val allMediaGridState = rememberViewportOnlyLazyGridState(
                                    initialFirstVisibleItemIndex = savedScroll.index,
                                    initialFirstVisibleItemScrollOffset = savedScroll.offset
                                )

                                LaunchedEffect(vaultState.items.isNotEmpty()) {
                                    if (vaultState.items.isNotEmpty() && savedScroll.index > 0 && allMediaGridState.firstVisibleItemIndex == 0) {
                                        allMediaGridState.scrollToItem(savedScroll.index, savedScroll.offset)
                                    }
                                }

                                val allMediaPreviewRefs = remember { mutableMapOf<String, Int>() }
                                AnimatedPinchGridContainer(
                                    currentColumns = gridColumns,
                                    onColumnsChanged = viewModel::setGridColumns,
                                    minColumns = 2,
                                    maxColumns = 7,
                                    gridState = allMediaGridState,
                                    initialFirstVisibleItemIndex = savedScroll.index,
                                    initialFirstVisibleItemScrollOffset = savedScroll.offset,
                                    onScrollPositionChanged = { idx, off ->
                                        viewModel.saveGridScrollPosition("vault_all", idx, off)
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    overlayContent = { activeGridState, _ ->
                                        VerticalFastScrollbar(
                                            gridState = activeGridState,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                ) { layerColumns, layerGridState, isPreviewLayer, tileAspectRatio ->
                                    val targetSize = gridThumbnailTargetSize(layerColumns)
                                    val isLayerScrolling by remember(layerGridState) {
                                        derivedStateOf { layerGridState.isScrollInProgress }
                                    }
                                    val effectiveScrolling = if (isPreviewLayer) false else isLayerScrolling

                                    LazyVerticalGrid(
                                        state = layerGridState,
                                        columns = GridCells.Fixed(layerColumns),
                                        contentPadding = PaddingValues(2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(vaultState.items, key = { it.id }) { vaultItem ->
                                            val isSelected = vaultItem.id in vaultState.selectedItemIds
                                            VaultThumbnailCard(
                                                item = vaultItem,
                                                previewFile = vaultState.previewFiles[vaultItem.id],
                                                previewBytes = vaultState.previewBytes[vaultItem.id],
                                                previewBitmap = vaultState.previewBitmaps[vaultItem.id],
                                                isCorrupted = vaultState.corruptedItemIds.contains(vaultItem.id),
                                                isScrolling = effectiveScrolling,
                                                isPreviewLayer = isPreviewLayer,
                                                activeRefCounts = allMediaPreviewRefs,
                                                targetSize = targetSize,
                                                aspectRatio = tileAspectRatio,
                                                isSelectionMode = vaultState.isSelectionMode,
                                                isSelected = isSelected,
                                                onRequestPreview = { viewModel.requestPreview(vaultItem) },
                                                onReleasePreview = { viewModel.releasePreview(vaultItem.id) },
                                                onClick = {
                                                    if (vaultState.isSelectionMode) {
                                                        viewModel.toggleSelection(vaultItem.id)
                                                    } else {
                                                        viewModel.saveGridScrollPosition("vault_all", layerGridState.firstVisibleItemIndex, layerGridState.firstVisibleItemScrollOffset)
                                                        viewModel.openViewer(vaultItem, vaultState.items)
                                                    }
                                                },
                                                onLongClick = {
                                                    viewModel.toggleSelection(vaultItem.id)
                                                },
                                                onEdit = {
                                                    viewModel.editItem(vaultItem) { file, isVideo ->
                                                        onEditItem(Uri.fromFile(file).toString(), isVideo)
                                                    }
                                                },
                                                onInfo = { itemInfoToShow = vaultItem },
                                                onRestore = { viewModel.restoreItemToGallery(vaultItem) },
                                                onDelete = { itemToDelete = vaultItem }
                                            )
                                        }
                                    }
                                }
                            }
                            VaultTab.ALBUMS -> {
                                if (vaultState.albums.isEmpty()) {
                                    EmptyStateView(
                                        icon = Icons.Outlined.FolderSpecial,
                                        title = "No Albums",
                                        subtitle = "Move photos and videos to Secure Folder albums from the gallery"
                                    )
                                } else {
                                    val savedScroll = remember { viewModel.getGridScrollPosition("vault_albums") }
                                    val albumsGridState = rememberViewportOnlyLazyGridState(
                                        initialFirstVisibleItemIndex = savedScroll.index,
                                        initialFirstVisibleItemScrollOffset = savedScroll.offset
                                    )

                                    LaunchedEffect(vaultState.albums.isNotEmpty()) {
                                        if (vaultState.albums.isNotEmpty() && savedScroll.index > 0 && albumsGridState.firstVisibleItemIndex == 0) {
                                            albumsGridState.scrollToItem(savedScroll.index, savedScroll.offset)
                                        }
                                    }

                                    LaunchedEffect(albumsGridState) {
                                        snapshotFlow {
                                            Triple(
                                                albumsGridState.firstVisibleItemIndex,
                                                albumsGridState.firstVisibleItemScrollOffset,
                                                albumsGridState.isScrollInProgress
                                            )
                                        }.collect { (idx, off, isScrolling) ->
                                            if (isScrolling || idx > 0 || off > 0) {
                                                viewModel.saveGridScrollPosition("vault_albums", idx, off)
                                            }
                                        }
                                    }

                                    val isAlbumsScrolling by remember { derivedStateOf { albumsGridState.isScrollInProgress } }
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        LazyVerticalGrid(
                                            state = albumsGridState,
                                            columns = GridCells.Fixed(2),
                                            contentPadding = PaddingValues(12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(vaultState.albums, key = { it.name }) { album ->
                                                VaultAlbumCard(
                                                    album = album,
                                                    coverFile = album.coverItem?.let { vaultState.previewFiles[it.id] },
                                                    coverBytes = album.coverItem?.let { vaultState.previewBytes[it.id] },
                                                    coverBitmap = album.coverItem?.let { vaultState.previewBitmaps[it.id] },
                                                    isScrolling = isAlbumsScrolling,
                                                    onRequestPreview = { viewModel.requestPreview(it) },
                                                    onReleasePreview = { viewModel.releasePreview(it.id) },
                                                    onClick = {
                                                        viewModel.saveGridScrollPosition("vault_albums", albumsGridState.firstVisibleItemIndex, albumsGridState.firstVisibleItemScrollOffset)
                                                        viewModel.openAlbum(album.name)
                                                    }
                                                )
                                            }
                                        }
                                        VerticalFastScrollbar(
                                            gridState = albumsGridState,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Full-screen Secure Media Viewer
            if (vaultState.activeViewingItem != null) {
                VaultMediaViewer(
                    items = displayedItems,
                    initialItem = vaultState.activeViewingItem!!,
                    decryptedFiles = vaultState.decryptedFiles,
                    isPreparing = vaultState.isPreparingViewer,
                    onPageChanged = { index -> viewModel.prepareViewingFiles(displayedItems, index) },
                    onClose = viewModel::closeViewer,
                    onRestore = { item -> viewModel.restoreItemToGallery(item) },
                    onEdit = { file, isVideo ->
                        viewModel.setLaunchingEditor(true)
                        onEditItem(Uri.fromFile(file).toString(), isVideo)
                    },
                    onDelete = { item -> itemToDelete = item }
                )
            }

            // Info / Error Snackbar
            (vaultState.infoMessage ?: vaultState.error)?.let { msg ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::dismissMessages) { Text("OK") }
                    }
                ) { Text(msg) }
            }
        }
    }

    // Delete confirmation dialog
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            icon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete permanently?") },
            text = { Text("\"${item.originalName}\" will be permanently deleted from the vault.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFromVault(item)
                        itemToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (itemInfoToShow != null) {
        VaultInfoBottomSheet(
            item = itemInfoToShow!!,
            decryptedFile = vaultState.decryptedFiles[itemInfoToShow!!.id] ?: vaultState.previewFiles[itemInfoToShow!!.id],
            onDismissRequest = { itemInfoToShow = null }
        )
    }

    // Batch delete confirmation dialog
    if (showBatchDeleteDialog) {
        val count = vaultState.selectedItemIds.size
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            icon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete permanently?") },
            text = { Text("$count item(s) will be permanently deleted from the vault.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchDeleteDialog = false
                        viewModel.removeSelectedFromVault()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showChangePinDialog) {
        var currentPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmNewPin by remember { mutableStateOf("") }
        var dialogError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showChangePinDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            icon = { Icon(Icons.Outlined.Pin, null) },
            title = { Text("Change Secure PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { if (it.length <= 6) currentPin = it },
                        label = { Text("Current 6-digit PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 6) newPin = it },
                        label = { Text("New 6-digit PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmNewPin,
                        onValueChange = { if (it.length <= 6) confirmNewPin = it },
                        label = { Text("Confirm New PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    dialogError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (currentPin.length != 6) {
                            dialogError = "Enter current 6-digit PIN"
                            return@TextButton
                        }
                        if (newPin.length != 6) {
                            dialogError = "New PIN must be 6 digits"
                            return@TextButton
                        }
                        if (newPin != confirmNewPin) {
                            dialogError = "New PINs do not match"
                            return@TextButton
                        }
                        viewModel.changePin(currentPin, newPin) { success, msg ->
                            if (success) {
                                showChangePinDialog = false
                            } else {
                                dialogError = msg
                            }
                        }
                    }
                ) {
                    Text("Update PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangePinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBiometricInfoDialog) {
        val isBioAvail = viewModel.biometricAuthManager.isBiometricAvailable()
        AlertDialog(
            onDismissRequest = { showBiometricInfoDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            icon = { Icon(Icons.Outlined.Fingerprint, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Fingerprint Unlock") },
            text = {
                Text(
                    if (isBioAvail)
                        "Fingerprint unlock is supported on your device and enabled for the Secure Folder. You can authenticate using your fingerprint or your 6-digit PIN anytime."
                    else
                        "Fingerprint / Biometric authentication is not set up on this device. You can configure it in your phone's Android Settings under Security & Privacy > Biometrics."
                )
            },
            confirmButton = {
                TextButton(onClick = { showBiometricInfoDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showDuplicateAnalyzerSheet) {
        DuplicateAnalyzerSheet(
            isScanning = vaultDuplicateScanState.isScanning,
            scannedCount = vaultDuplicateScanState.scannedCount,
            totalCount = vaultDuplicateScanState.totalCount,
            result = vaultDuplicateScanState.result,
            onDismiss = {
                showDuplicateAnalyzerSheet = false
                viewModel.clearVaultDuplicateScan()
            },
            onDeleteItem = { item -> viewModel.deleteDuplicateVaultItem(item) },
            onDeleteAllDuplicatesInGroup = { group -> viewModel.deleteAllDuplicatesInVaultGroup(group) },
            onRenameItem = { item, newName -> viewModel.renameDuplicateVaultItem(item, newName) },
            thumbnail = { item: VaultItem, modifier: Modifier ->
                val context = LocalContext.current
                val previewBytes = vaultState.previewBytes[item.id]
                val previewBitmap = vaultState.previewBitmaps[item.id]
                val previewFile = vaultState.previewFiles[item.id]

                LaunchedEffect(item.id) {
                    if (previewBytes == null && previewBitmap == null && previewFile == null) {
                        viewModel.requestPreview(item)
                    }
                }

                Box(modifier = modifier, contentAlignment = Alignment.Center) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = item.originalName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if ((previewBytes != null && previewBytes.isNotEmpty()) || (previewFile != null && previewFile.exists())) {
                        val thumbRequest = remember(previewBytes, previewFile?.absolutePath, item.id) {
                            ImageRequest.Builder(context)
                                .data(previewBytes ?: previewFile)
                                .size(150, 150)
                                .precision(Precision.INEXACT)
                                .scale(Scale.FILL)
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .build()
                        }
                        AsyncImage(
                            model = thumbRequest,
                            contentDescription = item.originalName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (item.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Photo,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    if (item.isVideo) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun VaultAlbumCard(
    album: VaultAlbum,
    coverBitmap: Bitmap? = null,
    coverFile: File? = null,
    coverBytes: ByteArray? = null,
    onClick: () -> Unit,
    isScrolling: Boolean = false,
    onRequestPreview: (VaultItem) -> Unit = {},
    onReleasePreview: (VaultItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coverItem = album.coverItem
    val cacheKey = "vault_thumb_${coverItem?.id ?: album.name}"
    val imageLoader = context.imageLoader
    val isInMemoryCache = imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null
    var hasLoadedInViewport by remember(cacheKey) {
        mutableStateOf(isInMemoryCache || !isScrolling)
    }
    val shouldLoad = hasLoadedInViewport || isInMemoryCache || !isScrolling
    SideEffect {
        if (shouldLoad && !hasLoadedInViewport) {
            hasLoadedInViewport = true
        }
    }
    DisposableEffect(cacheKey) {
        onDispose {
            hasLoadedInViewport = false
            imageLoader.memoryCache?.remove(MemoryCache.Key(cacheKey))
            if (coverItem != null) {
                onReleasePreview(coverItem)
            }
        }
    }
    LaunchedEffect(coverItem?.id, shouldLoad, coverBitmap, coverBytes, coverFile) {
        if (shouldLoad && coverItem != null && coverBitmap == null && coverBytes == null && coverFile == null) {
            onRequestPreview(coverItem)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (shouldLoad && coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap.asImageBitmap(),
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (shouldLoad && ((coverBytes != null && coverBytes.isNotEmpty()) || (coverFile != null && coverFile.exists()))) {
                    val thumbRequest = remember(coverBytes, coverFile?.absolutePath, cacheKey) {
                        ImageRequest.Builder(context)
                            .data(coverBytes ?: coverFile)
                            .size(180, 180)
                            .precision(Precision.INEXACT)
                            .scale(Scale.FILL)
                            .bitmapConfig(Bitmap.Config.RGB_565)
                            .allowHardware(true)
                            .memoryCacheKey(cacheKey)
                            .diskCacheKey(cacheKey)
                            .crossfade(false)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .build()
                    }
                    AsyncImage(
                        model = thumbRequest,
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.FolderSpecial,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = "${album.items.size} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultThumbnailCard(
    item: VaultItem,
    previewBitmap: Bitmap? = null,
    previewFile: File? = null,
    previewBytes: ByteArray? = null,
    isCorrupted: Boolean,
    onClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
    onInfo: () -> Unit = {},
    isScrolling: Boolean = false,
    isPreviewLayer: Boolean = false,
    activeRefCounts: MutableMap<String, Int>? = null,
    targetSize: Int = 150,
    aspectRatio: Float = 1f,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
    onRequestPreview: () -> Unit = {},
    onReleasePreview: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val cacheKey = "vault_thumb_${item.id}"
    val imageLoader = context.imageLoader
    val isInMemoryCache = imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null
    var hasLoadedInViewport by remember(cacheKey) {
        mutableStateOf(isPreviewLayer || isInMemoryCache || !isScrolling)
    }
    val shouldLoad = isPreviewLayer || hasLoadedInViewport || isInMemoryCache || !isScrolling
    SideEffect {
        if (shouldLoad && !hasLoadedInViewport) {
            hasLoadedInViewport = true
        }
    }
    DisposableEffect(cacheKey) {
        if (activeRefCounts != null) {
            activeRefCounts[cacheKey] = (activeRefCounts[cacheKey] ?: 0) + 1
        }
        onDispose {
            hasLoadedInViewport = false
            val remaining = if (activeRefCounts != null) {
                val next = ((activeRefCounts[cacheKey] ?: 1) - 1).coerceAtLeast(0)
                if (next == 0) activeRefCounts.remove(cacheKey) else activeRefCounts[cacheKey] = next
                next
            } else {
                0
            }
            if (remaining == 0) {
                imageLoader.memoryCache?.remove(MemoryCache.Key(cacheKey))
                onReleasePreview()
            }
        }
    }
    LaunchedEffect(item.id, shouldLoad, previewBitmap, previewBytes, previewFile) {
        if (shouldLoad && previewBitmap == null && previewBytes == null && previewFile == null) {
            onRequestPreview()
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
    ) {
        if (shouldLoad) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = item.originalName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if ((previewBytes != null && previewBytes.isNotEmpty()) || (previewFile != null && previewFile.exists())) {
                val decodeSize = if (isPreviewLayer) 110 else targetSize
                val thumbRequest = remember(previewBytes, previewFile?.absolutePath, item.id, decodeSize) {
                    ImageRequest.Builder(context)
                        .data(previewBytes ?: previewFile)
                        .size(decodeSize, decodeSize)
                        .precision(Precision.INEXACT)
                        .scale(Scale.FILL)
                        .bitmapConfig(Bitmap.Config.RGB_565)
                        .allowHardware(true)
                        .memoryCacheKey(cacheKey)
                        .diskCacheKey(cacheKey)
                        .placeholderMemoryCacheKey(cacheKey)
                        .crossfade(false)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build()
                }
                AsyncImage(
                    model = thumbRequest,
                    contentDescription = item.originalName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (isCorrupted) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BrokenImage,
                            contentDescription = "Corrupted item",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Damaged",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                // Decrypting placeholder with lock badge
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (item.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Lock,
                            contentDescription = item.originalName,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }

        // Dim / tint overlay when selected
        if (isSelectionMode && isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.32f))
            )
        }

        // Selection mode checkbox indicator (top-end)
        if (isSelectionMode) {
            val checkSize = 22.dp
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(checkSize)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(checkSize)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                            .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                    )
                }
            }
        }

        // Video badge
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("Video", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // More options dropdown (hidden during selection mode so tapping toggles selection)
        if (!isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
            ) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "Options",
                        tint = Color.White,
                        modifier = Modifier
                            .size(18.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .padding(2.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f))
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Details") },
                        leadingIcon = { Icon(Icons.Outlined.Info, null) },
                        onClick = {
                            showMenu = false
                            onInfo()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Take Out of Secure Folder") },
                        leadingIcon = { Icon(Icons.Outlined.LockOpen, null) },
                        onClick = {
                            showMenu = false
                            onRestore()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete permanently") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultMediaViewer(
    items: List<VaultItem>,
    initialItem: VaultItem,
    decryptedFiles: Map<String, File>,
    isPreparing: Boolean,
    onPageChanged: (Int) -> Unit,
    onClose: () -> Unit,
    onRestore: (VaultItem) -> Unit,
    onEdit: (File, Boolean) -> Unit,
    onDelete: (VaultItem) -> Unit
) {
    val initialIndex = remember(initialItem.id) {
        items.indexOfFirst { it.id == initialItem.id }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialIndex) { items.size }
    var isCurrentMediaZoomed by remember { mutableStateOf(false) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var isScrubbing by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }

    // Intercept Back button
    BackHandler {
        onClose()
    }

    // Automatically close viewer if displayed items become empty
    LaunchedEffect(items.isEmpty()) {
        if (items.isEmpty()) {
            onClose()
        }
    }

    // Clamp pager cleanly to the new lastIndex if the final item was deleted
    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && pagerState.currentPage >= items.size) {
            pagerState.scrollToPage(items.lastIndex)
        }
    }

    val currentItem = items.getOrNull(pagerState.currentPage)
        ?: items.getOrNull(items.lastIndex)
        ?: initialItem
    val currentDecryptedFile = decryptedFiles[currentItem.id]

    // Reset zoom and notify page change when current item changes
    LaunchedEffect(currentItem.id) {
        isCurrentMediaZoomed = false
        val targetIndex = items.indexOfFirst { it.id == currentItem.id }.coerceAtLeast(0)
        onPageChanged(targetIndex)
    }

    LaunchedEffect(areControlsVisible, isScrubbing) {
        if (areControlsVisible && !isScrubbing) {
            delay(3500)
            areControlsVisible = false
        }
    }

    SwipeToDismissBox(
        onDismiss = onClose,
        enabled = !isCurrentMediaZoomed,
        onSwipeUp = { showInfoSheet = true }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 16.dp,
                key = { items.getOrNull(it)?.id ?: it },
                userScrollEnabled = !isCurrentMediaZoomed,
                beyondViewportPageCount = 1
            ) { page ->
                val item = items.getOrNull(page) ?: return@HorizontalPager
                val pageFile = decryptedFiles[item.id]
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
                    if (pageFile != null && pageFile.exists()) {
                        if (item.isVideo) {
                            VaultVideoPlayer(
                                file = pageFile,
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
                        } else {
                            VaultZoomablePhoto(
                                file = pageFile,
                                item = item,
                                onTap = { areControlsVisible = !areControlsVisible },
                                onZoomChanged = { zoomed ->
                                    if (page == pagerState.currentPage) {
                                        isCurrentMediaZoomed = zoomed
                                    }
                                }
                            )
                        }
                    } else {
                        if (page == pagerState.currentPage) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Decrypting secure media...",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Box(Modifier.fillMaxSize(), Alignment.Center) {
                                CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }

            // Overlay controls
            AnimatedVisibility(
                visible = areControlsVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Top Action Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .align(Alignment.TopCenter),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close", tint = Color.White)
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = currentItem.originalName,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                            if (items.size > 1) {
                                Text(
                                    text = "${pagerState.currentPage.coerceIn(0, items.lastIndex) + 1} of ${items.size}",
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        // Info / Details
                        IconButton(onClick = { showInfoSheet = true }) {
                            Icon(Icons.Outlined.Info, contentDescription = "Details", tint = Color.White)
                        }

                        // Take out of Secure Folder back to Main Grid
                        IconButton(onClick = { onRestore(currentItem) }) {
                            Icon(Icons.Outlined.LockOpen, contentDescription = "Take Out of Secure Folder", tint = Color.White)
                        }

                        // Edit Photo / Video
                        if (currentDecryptedFile != null && currentDecryptedFile.exists() && currentDecryptedFile.length() > 0L) {
                            IconButton(onClick = { onEdit(currentDecryptedFile, currentItem.isVideo) }) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = if (currentItem.isVideo) "Edit video" else "Edit photo",
                                    tint = Color.White
                                )
                            }
                        }

                        // Delete
                        IconButton(onClick = { onDelete(currentItem) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Color.White)
                        }
                    }

                    // Bottom bar with Quick Action pill
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(16.dp)
                            .align(Alignment.BottomCenter),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = { onRestore(currentItem) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(Icons.Outlined.LockOpen, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Take Out of Secure Folder")
                        }
                    }
                }
            }
        }
    }

    if (showInfoSheet) {
        VaultInfoBottomSheet(
            item = currentItem,
            decryptedFile = currentDecryptedFile,
            onDismissRequest = { showInfoSheet = false }
        )
    }
}

@Composable
private fun VaultZoomablePhoto(
    file: File,
    item: VaultItem,
    onTap: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {}
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
    ) {
        val context = LocalContext.current
        val fullResRequest = remember(file.absolutePath, item.id) {
            ImageRequest.Builder(context)
                .data(file)
                .size(coil.size.Size.ORIGINAL)
                .precision(coil.size.Precision.EXACT)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .allowHardware(false)
                .placeholderMemoryCacheKey("vault_thumb_${item.id}")
                .memoryCacheKey("orig_vault_${item.id}")
                .diskCacheKey("orig_vault_${item.id}")
                .crossfade(200)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        }

        AsyncImage(
            model = fullResRequest,
            contentDescription = item.originalName,
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

private fun formatVaultTime(ms: Float): String {
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
private fun VaultVideoPlayer(
    file: File,
    isActivePage: Boolean = true,
    areControlsVisible: Boolean = true,
    onTap: () -> Unit = {},
    onScrubbingChanged: (Boolean) -> Unit = {},
    onVideoEnded: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var isMuted by remember(file.absolutePath) { mutableStateOf(false) }

    var scale by remember(file.absolutePath) { mutableFloatStateOf(1f) }
    var offset by remember(file.absolutePath) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(file.absolutePath) { mutableStateOf(IntSize.Zero) }
    val coroutineScope = rememberCoroutineScope()
    var animationJob by remember(file.absolutePath) { mutableStateOf<Job?>(null) }

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

    LaunchedEffect(file.absolutePath) {
        animationJob?.cancel()
        scale = 1f
        offset = Offset.Zero
        currentOnZoomChanged(false)
    }

    val exoPlayer = remember(file.absolutePath) {
        val extractorsFactory = DefaultExtractorsFactory().apply {
            setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build().apply {
                setMediaItem(ExoMediaItem.fromUri(Uri.fromFile(file)))
                volume = if (isMuted) 0f else 1f
                prepare()
                playWhenReady = isActivePage
            }
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    val fileDuration = remember(file.absolutePath) {
        try {
            val retriever = android.media.MediaMetadataRetriever()
            java.io.FileInputStream(file).use { fis ->
                retriever.setDataSource(fis.fd)
            }
            val time = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            time?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    var isPlaying by remember(file.absolutePath) { mutableStateOf(exoPlayer.isPlaying) }
    var playbackState by remember(file.absolutePath) { mutableIntStateOf(exoPlayer.playbackState) }
    var currentPositionMs by remember(file.absolutePath) { mutableFloatStateOf(0f) }
    var durationMs by remember(file.absolutePath) {
        mutableFloatStateOf(if (fileDuration > 0L) fileDuration.toFloat() else 0f)
    }
    var isScrubbing by remember(file.absolutePath) { mutableStateOf(false) }
    var isSeeking by remember(file.absolutePath) { mutableStateOf(false) }
    var scrubPositionMs by remember(file.absolutePath) { mutableFloatStateOf(0f) }
    var lastSeekTimestamp by remember(file.absolutePath) { mutableLongStateOf(0L) }

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

    // Periodically update position while playing (with guard against stale/zero position after seek)
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
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.vault_texture_player_view, null) as PlayerView).apply {
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
                .pointerInput(file.absolutePath) {
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
                .pointerInput(file.absolutePath) {
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
                    .padding(bottom = 84.dp, start = 12.dp, end = 12.dp)
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
                        text = formatVaultTime(displayPos),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 2.dp, end = 4.dp)
                    )

                    // Sleek Video Scrubber
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
                        text = formatVaultTime(durationMs),
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
