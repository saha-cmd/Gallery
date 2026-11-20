package com.gallery.feature.gallery.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gallery.core.data.permissions.MediaPermissionManager
import com.gallery.core.ui.components.DirectVaultTransferPermissionDialog
import coil.imageLoader
import coil.memory.MemoryCache
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import com.gallery.core.domain.model.Album
import com.gallery.core.domain.model.MediaItem
import com.gallery.core.ui.components.AddToAlbumDialog
import com.gallery.core.ui.components.AnimatedPinchGridContainer
import com.gallery.core.ui.components.AppUpdateDialog
import com.gallery.core.ui.components.ChangeAlbumCoverDialog
import com.gallery.core.ui.components.CreateAlbumDialog
import com.gallery.core.ui.components.DuplicateAnalyzerSheet
import com.gallery.core.ui.components.EditGroupAlbumsDialog
import com.gallery.core.ui.components.EmptyStateView
import com.gallery.core.ui.components.GridScrollPosition
import com.gallery.core.ui.components.GroupAlbumsDialog
import com.gallery.core.ui.components.MediaThumbnail
import com.gallery.core.ui.components.MoveAlbumToGroupDialog
import com.gallery.core.ui.components.MoveAlbumToVaultDialog
import com.gallery.core.ui.components.MoveToAlbumDialog
import com.gallery.core.ui.components.MoveToGroupDialog
import com.gallery.core.ui.components.MoveToVaultAlbumDialog
import com.gallery.core.ui.components.RenameAlbumDialog
import com.gallery.core.ui.components.VerticalFastScrollbar
import com.gallery.core.ui.components.gridThumbnailTargetSize
import com.gallery.core.ui.components.rememberViewportOnlyLazyGridState
import com.gallery.feature.gallery.viewmodel.AlbumGroupSection
import com.gallery.feature.gallery.viewmodel.GalleryTab
import com.gallery.feature.gallery.viewmodel.GalleryViewModel
import com.gallery.feature.gallery.viewmodel.MediaGroup
import com.gallery.feature.gallery.viewmodel.UserAlbumGroupUiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onMediaClick: (Long, Boolean, Long) -> Unit,
    onVaultClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val vaultAlbums by viewModel.vaultAlbums.collectAsStateWithLifecycle()
    val gridColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val selectedAlbum by viewModel.selectedAlbum.collectAsStateWithLifecycle()
    val isGroupedView by viewModel.isGroupedView.collectAsStateWithLifecycle()
    val collapsedGroups by viewModel.collapsedGroups.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val duplicateScanState by viewModel.duplicateScanState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = selectedTab.ordinal,
        pageCount = { GalleryTab.entries.size }
    )

    // Sync swiped page -> viewModel.selectedTab
    LaunchedEffect(pagerState.currentPage) {
        val targetTab = GalleryTab.entries[pagerState.currentPage]
        if (selectedTab != targetTab) {
            viewModel.onTabSelected(targetTab)
        }
    }

    // Sync viewModel.selectedTab -> pagerState
    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab.ordinal && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(selectedTab.ordinal)
        }
    }

    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showDeleteAlbumsConfirmation by remember { mutableStateOf(false) }
    var showMoveToVaultDialog by remember { mutableStateOf(false) }
    var showMoveAlbumsToVaultDialog by remember { mutableStateOf(false) }
    var showMoveToAlbumDialog by remember { mutableStateOf(false) }
    var showAddToAlbumDialog by remember { mutableStateOf(false) }
    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    var albumToMoveToGroup by remember { mutableStateOf<Album?>(null) }
    var showMoveAlbumsToGroupDialog by remember { mutableStateOf(false) }
    var albumToRename by remember { mutableStateOf<Album?>(null) }
    var albumToChangeCover by remember { mutableStateOf<Album?>(null) }
    var showGroupAlbumsDialog by remember { mutableStateOf(false) }
    var preselectedAlbumKeysForGroup by remember { mutableStateOf<Set<String>>(emptySet()) }
    var groupToEditAlbums by remember { mutableStateOf<UserAlbumGroupUiModel?>(null) }
    var groupToRename by remember { mutableStateOf<UserAlbumGroupUiModel?>(null) }
    var groupToDissolve by remember { mutableStateOf<UserAlbumGroupUiModel?>(null) }
    var isRecycleBinOpen by remember { mutableStateOf(false) }
    var showDuplicateAnalyzerSheet by remember { mutableStateOf(false) }

    // Clear album selection when leaving Albums tab
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != GalleryTab.ALBUMS.ordinal) {
            viewModel.clearAlbumSelection()
        }
    }

    if (isRecycleBinOpen) {
        RecycleBinScreen(onBack = { isRecycleBinOpen = false })
        return
    }

    selectedAlbum?.let { album ->
        AlbumDetailScreen(
            album = album,
            onBack = viewModel::closeAlbum,
            onMediaClick = { id, isVideo -> onMediaClick(id, isVideo, album.id) },
            onMoveItemsToVault = { items, albumName -> viewModel.moveItemsToVault(items, albumName) },
            onDeleteItemsToRecycleBin = viewModel::moveItemsToRecycleBin,
            vaultAlbums = vaultAlbums,
            allMedia = uiState.allMedia,
            customAlbums = uiState.customAlbums,
            albums = uiState.albums,
            onMoveItemsToAlbum = { items, targetAlbum, newAlbumName ->
                viewModel.moveItemsToAlbum(items, targetAlbum, newAlbumName, album.id)
            },
            pendingMoveIntentSender = uiState.pendingMoveIntentSender,
            onRetryMoveAfterPermission = viewModel::retryPendingMove,
            onClearPendingMoveIntent = viewModel::clearPendingMove,
            pendingDeleteIntentSender = uiState.pendingVaultDeleteIntentSender ?: uiState.pendingDeleteIntentSender,
            onConfirmDeleteIntent = viewModel::onVaultDeleteConfirmed,
            onCancelDeleteIntent = viewModel::onVaultDeleteCancelled,
            onClearPendingDeleteIntent = viewModel::clearPendingVaultDeleteIntent,
            showDirectTransferPermissionDialog = uiState.showDirectTransferPermissionDialog,
            onDismissDirectTransferDialog = viewModel::dismissDirectTransferPermissionDialog,
            onToggleFavoritesForSelected = { viewModel.toggleFavoritesForSelected() },
            onAddSelectedToCustomAlbum = { targetAlbumId, _ -> viewModel.addSelectedToCustomAlbum(targetAlbumId) },
            onCreateAndAddSelectedToCustomAlbum = { name, _ -> viewModel.createAndAddSelectedToCustomAlbum(name) },
            onRemoveSelectedFromAlbum = { albumId, items -> viewModel.removeSelectedFromCustomAlbum(albumId, items) },
            onRenameAlbum = { albumId, newName -> viewModel.renameCustomAlbum(albumId, newName) },
            onDeleteAlbum = { albumId -> viewModel.deleteCustomAlbum(albumId) },
            onAddItemsToAlbum = { albumId, items -> viewModel.addItemsToCustomAlbum(albumId, items) },
            getSavedScrollPosition = viewModel::getGridScrollPosition,
            onSaveScrollPosition = viewModel::saveGridScrollPosition,
            columnCount = gridColumns,
            onColumnCountChanged = viewModel::setGridColumns
        )
        return
    }

    // Intercept hardware/gesture Back button when in album selection mode
    BackHandler(enabled = uiState.isAlbumSelectionMode) {
        viewModel.clearAlbumSelection()
    }

    // Intercept hardware/gesture Back button when in item selection mode
    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    // System delete confirmation launcher for Android 11+ Scoped Storage
    val systemDeleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onVaultDeleteConfirmed()
        } else {
            viewModel.onVaultDeleteCancelled()
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
                    systemDeleteLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(it).build()
                    )
                    viewModel.clearPendingVaultDeleteIntent()
                }
            }
        )
    }

    val activeVaultDeleteSender = uiState.pendingVaultDeleteIntentSender ?: uiState.pendingDeleteIntentSender
    LaunchedEffect(activeVaultDeleteSender, uiState.showDirectTransferPermissionDialog) {
        if (!uiState.showDirectTransferPermissionDialog) {
            activeVaultDeleteSender?.let { sender ->
                systemDeleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                )
                viewModel.clearPendingVaultDeleteIntent()
            }
        }
    }

    // Move to album permission launcher for Scoped Storage
    val movePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.retryPendingMove()
        } else {
            viewModel.clearPendingMove()
        }
    }

    LaunchedEffect(uiState.pendingMoveIntentSender) {
        uiState.pendingMoveIntentSender?.let { sender ->
            movePermissionLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(sender).build()
            )
        }
    }

    val renamePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.retryPendingDuplicateRename()
        } else {
            viewModel.clearPendingDuplicateRename()
        }
    }

    LaunchedEffect(duplicateScanState.pendingRenameIntentSender) {
        duplicateScanState.pendingRenameIntentSender?.let { sender ->
            renamePermissionLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(sender).build()
            )
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) viewModel.onPermissionGranted()
    }

    LaunchedEffect(Unit) {
        if (!uiState.hasPermission) {
            val permissions = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permissionLauncher.launch(permissions)
        }
    }

    // Share selected media helper function
    fun shareSelectedMedia() {
        val selected = uiState.selectedItems
        if (selected.isEmpty()) return
        val uris = ArrayList(selected.map { it.uri })
        val shareIntent = Intent().apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            type = if (selected.all { it is MediaItem.Photo }) "image/*"
            else if (selected.all { it is MediaItem.Video }) "video/*"
            else "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share ${selected.size} items"))
    }

    // Share selected albums helper function
    fun shareSelectedAlbums() {
        val selected = uiState.selectedAlbums
        val allItems = selected.flatMap { it.items }.distinctBy { it.id }
        if (allItems.isEmpty()) {
            viewModel.showMessage("Selected album is empty")
            return
        }
        val uris = ArrayList(allItems.map { it.uri })
        val shareIntent = Intent().apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            type = if (allItems.all { it is MediaItem.Photo }) "image/*"
            else if (allItems.all { it is MediaItem.Video }) "video/*"
            else "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share ${selected.size} album${if (selected.size != 1) "s" else ""}"))
    }

    Scaffold(
        topBar = {
            if (pagerState.currentPage == GalleryTab.ALBUMS.ordinal && uiState.isAlbumSelectionMode) {
                AlbumSelectionTopBar(
                    selectedCount = uiState.selectedAlbumIds.size,
                    selectedAlbums = uiState.selectedAlbums,
                    onClearSelection = viewModel::clearAlbumSelection,
                    onSelectAll = viewModel::selectAllAlbums,
                    onShare = { shareSelectedAlbums() },
                    onDelete = { showDeleteAlbumsConfirmation = true },
                    onMoveToVault = { showMoveAlbumsToVaultDialog = true },
                    onMoveToGroup = { showMoveAlbumsToGroupDialog = true },
                    onRenameAlbum = { albumToRename = it },
                    onChangeCover = { albumToChangeCover = it }
                )
            } else if (uiState.isSelectionMode) {
                SelectionTopBar(
                    selectedCount = uiState.selectedItemIds.size,
                    onClearSelection = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onShare = { shareSelectedMedia() },
                    onDelete = { showDeleteConfirmation = true },
                    onMoveToVault = { showMoveToVaultDialog = true },
                    onToggleFavorite = viewModel::toggleFavoritesForSelected,
                    onAddToAlbum = { showAddToAlbumDialog = true },
                    onMoveToAlbum = { showMoveToAlbumDialog = true }
                )
            } else {
                GalleryTopBar(
                    selectedTab = selectedTab,
                    isGroupedView = isGroupedView,
                    onVaultClick = onVaultClick,
                    onRecycleBinClick = { isRecycleBinOpen = true },
                    onSettingsClick = onSettingsClick,
                    onCreateAlbumClick = { showCreateAlbumDialog = true },
                    onToggleGroupedView = viewModel::toggleGroupedView,
                    onGroupAlbumsClick = {
                        preselectedAlbumKeysForGroup = emptySet()
                        showGroupAlbumsDialog = true
                    },
                    onFindDuplicatesClick = {
                        viewModel.startDuplicateScan()
                        showDuplicateAnalyzerSheet = true
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                !uiState.hasPermission && uiState.isLoading -> PermissionPrompt {
                    permissionLauncher.launch(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                        else
                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    )
                }
                uiState.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !uiState.isSelectionMode && !uiState.isAlbumSelectionMode,
                        beyondViewportPageCount = 0
                    ) { page ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                                    val clamped = pageOffset.coerceIn(0f, 1f)
                                    val scale = lerp(1f, 0.94f, clamped)
                                    val alpha = lerp(1f, 0.55f, clamped)
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                }
                        ) {
                            when (GalleryTab.entries[page]) {
                                GalleryTab.TIMELINE -> TimelineGrid(
                                    mediaGroups = uiState.mediaGroups,
                                    monthlyMediaGroups = uiState.monthlyMediaGroups,
                                    columns = gridColumns,
                                    onColumnsChanged = viewModel::setGridColumns,
                                    savedScroll = remember { viewModel.getGridScrollPosition("timeline") },
                                    onSaveScrollPosition = { idx, off -> viewModel.saveGridScrollPosition("timeline", idx, off) },
                                    isSelectionMode = uiState.isSelectionMode,
                                    selectedIds = uiState.selectedItemIds,
                                    isParentScrolling = { pagerState.isScrollInProgress },
                                    onItemClick = { item ->
                                        if (uiState.isSelectionMode) {
                                            viewModel.toggleSelection(item.id)
                                        } else {
                                            onMediaClick(item.id, item is MediaItem.Video, -1L)
                                        }
                                    },
                                    onItemLongClick = { item ->
                                        if (uiState.isSelectionMode) {
                                            viewModel.toggleSelection(item.id)
                                        } else {
                                            viewModel.startSelection(item.id)
                                        }
                                    }
                                )
                                GalleryTab.ALBUMS -> AlbumsGrid(
                                    albums = uiState.albums,
                                    userAlbumGroups = uiState.userAlbumGroups,
                                    ungroupedAlbums = uiState.ungroupedAlbums,
                                    albumGroups = uiState.albumGroups,
                                    isGroupedView = isGroupedView,
                                    collapsedGroups = collapsedGroups,
                                    savedScroll = remember { viewModel.getGridScrollPosition("albums") },
                                    onSaveScrollPosition = { idx, off -> viewModel.saveGridScrollPosition("albums", idx, off) },
                                    isAlbumSelectionMode = uiState.isAlbumSelectionMode,
                                    selectedAlbumIds = uiState.selectedAlbumIds,
                                    isParentScrolling = { pagerState.isScrollInProgress },
                                    onToggleGroupCollapsed = { viewModel.toggleGroupCollapsed(it) },
                                    onAlbumClick = { album ->
                                        if (uiState.isAlbumSelectionMode) {
                                            viewModel.toggleAlbumSelection(album.id)
                                        } else {
                                            viewModel.selectAlbum(album)
                                        }
                                    },
                                    onAlbumLongClick = { album ->
                                        if (uiState.isAlbumSelectionMode) {
                                            viewModel.toggleAlbumSelection(album.id)
                                        } else {
                                            viewModel.startAlbumSelection(album.id)
                                        }
                                    },
                                    onEditGroupAlbums = { group -> groupToEditAlbums = group },
                                    onRenameGroup = { group -> groupToRename = group },
                                    onDissolveGroup = { group -> groupToDissolve = group }
                                )
                                GalleryTab.VIDEOS -> VideosGrid(
                                    videos = uiState.videos,
                                    columns = gridColumns,
                                    onColumnsChanged = viewModel::setGridColumns,
                                    savedScroll = remember { viewModel.getGridScrollPosition("videos") },
                                    onSaveScrollPosition = { idx, off -> viewModel.saveGridScrollPosition("videos", idx, off) },
                                    isSelectionMode = uiState.isSelectionMode,
                                    selectedIds = uiState.selectedItemIds,
                                    isParentScrolling = { pagerState.isScrollInProgress },
                                    onVideoClick = { video ->
                                        if (uiState.isSelectionMode) {
                                            viewModel.toggleSelection(video.id)
                                        } else {
                                            onMediaClick(video.id, true, -2L)
                                        }
                                    },
                                    onVideoLongClick = { video ->
                                        if (uiState.isSelectionMode) {
                                            viewModel.toggleSelection(video.id)
                                        } else {
                                            viewModel.startSelection(video.id)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Floating Bottom Navigation Bar (Timeline, Albums, Videos)
            AnimatedVisibility(
                visible = !uiState.isSelectionMode && !uiState.isAlbumSelectionMode,
                enter = slideInVertically(initialOffsetY = { it * 2 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it * 2 }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 16.dp)
            ) {
                FloatingGalleryBottomNav(
                    selectedTab = GalleryTab.entries[pagerState.currentPage],
                    onTabSelected = { tab ->
                        viewModel.onTabSelected(tab)
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(tab.ordinal)
                        }
                    }
                )
            }

            // Batch operation blocking overlay
            if (uiState.isPerformingBatchAction) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = uiState.batchActionMessage ?: "Processing...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Status feedback Snackbar
            uiState.error?.let { message ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::dismissError) { Text("OK") }
                    }
                ) { Text(message) }
            }
        }
    }

    // Add to Custom Album Dialog (for batch selected items in Timeline/Videos)
    if (showAddToAlbumDialog) {
        AddToAlbumDialog(
            customAlbums = uiState.customAlbums,
            onDismissRequest = { showAddToAlbumDialog = false },
            onSelectAlbum = { albumId ->
                showAddToAlbumDialog = false
                viewModel.addSelectedToCustomAlbum(albumId)
            },
            onCreateNewAlbum = { newName ->
                showAddToAlbumDialog = false
                viewModel.createAndAddSelectedToCustomAlbum(newName)
            },
            itemCount = uiState.selectedItemIds.size
        )
    }

    // Move to Album Dialog (for batch selected items in Timeline/Videos)
    if (showMoveToAlbumDialog) {
        MoveToAlbumDialog(
            itemCount = uiState.selectedItemIds.size,
            albums = uiState.albums,
            currentAlbumId = null,
            onDismiss = { showMoveToAlbumDialog = false },
            onConfirm = { targetAlbum, newAlbumName ->
                showMoveToAlbumDialog = false
                viewModel.moveSelectedToAlbum(targetAlbum, newAlbumName)
            }
        )
    }

    // Create New Custom Album Dialog
    if (showCreateAlbumDialog) {
        CreateAlbumDialog(
            title = "Create New Album",
            onDismissRequest = { showCreateAlbumDialog = false },
            onConfirm = { name ->
                showCreateAlbumDialog = false
                viewModel.createCustomAlbum(name)
            }
        )
    }

    // Move Album to Group Dialog
    albumToMoveToGroup?.let { album ->
        val existingGroupNames = remember(uiState.albumGroups) {
            uiState.albumGroups.map { it.title }.distinct()
        }
        val currentGroup = uiState.userGroupOverrides["album_${album.id}"]

        MoveToGroupDialog(
            album = album,
            existingGroups = existingGroupNames,
            currentGroupName = currentGroup,
            onDismissRequest = { albumToMoveToGroup = null },
            onSelectGroup = { groupName ->
                viewModel.setAlbumGroup(album, groupName)
                albumToMoveToGroup = null
            },
            onResetToDefault = {
                viewModel.resetAlbumGroup(album)
                albumToMoveToGroup = null
            }
        )
    }

    // Delete Selected Albums Dialog
    if (showDeleteAlbumsConfirmation) {
        val count = uiState.selectedAlbumIds.size
        val anyCustom = uiState.selectedAlbums.any { it.isCustom }
        AlertDialog(
            onDismissRequest = { showDeleteAlbumsConfirmation = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text("Delete $count album${if (count != 1) "s" else ""}?")
            },
            text = {
                Text(
                    text = "All photos and videos in the selected album${if (count != 1) "s" else ""} will be moved to the Recycle Bin." +
                        if (anyCustom) " Custom album entries will also be deleted." else ""
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAlbumsConfirmation = false
                        viewModel.deleteSelectedAlbums()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAlbumsConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Move Selected Albums to Group Dialog
    if (showMoveAlbumsToGroupDialog) {
        val existingGroupNames = remember(uiState.userAlbumGroups, uiState.albumGroups) {
            (uiState.userAlbumGroups.map { it.name } + uiState.albumGroups.map { it.title })
                .distinct()
                .filter { it.isNotBlank() }
        }
        val canUngroup = uiState.selectedAlbums.any { album ->
            uiState.userGroupOverrides.containsKey("album_${album.id}") ||
            uiState.userAlbumGroups.any { group -> group.albums.any { it.id == album.id } }
        }
        MoveAlbumToGroupDialog(
            albums = uiState.selectedAlbums,
            existingGroups = existingGroupNames,
            canUngroup = canUngroup,
            onDismissRequest = { showMoveAlbumsToGroupDialog = false },
            onSelectGroup = { groupName ->
                viewModel.moveSelectedAlbumsToGroup(groupName)
                showMoveAlbumsToGroupDialog = false
            },
            onUngroup = {
                viewModel.ungroupSelectedAlbums()
                showMoveAlbumsToGroupDialog = false
            }
        )
    }

    // Move Selected Albums to Secure Vault Dialog
    if (showMoveAlbumsToVaultDialog) {
        MoveAlbumToVaultDialog(
            selectedAlbums = uiState.selectedAlbums,
            existingVaultAlbums = vaultAlbums,
            onDismiss = { showMoveAlbumsToVaultDialog = false },
            onConfirm = { keepOriginalNames, targetVaultAlbum ->
                viewModel.moveSelectedAlbumsToVault(keepOriginalNames, targetVaultAlbum)
                showMoveAlbumsToVaultDialog = false
            }
        )
    }

    // Rename Album Dialog
    albumToRename?.let { album ->
        RenameAlbumDialog(
            initialName = album.name,
            onDismissRequest = { albumToRename = null },
            onConfirm = { newName ->
                viewModel.renameAlbum(album, newName)
                albumToRename = null
            }
        )
    }

    // Change Album Cover Dialog
    albumToChangeCover?.let { album ->
        ChangeAlbumCoverDialog(
            album = album,
            onDismissRequest = { albumToChangeCover = null },
            onSelectCover = { uri ->
                viewModel.setAlbumCover(album, uri)
                albumToChangeCover = null
            }
        )
    }

    // Group Albums Dialog
    if (showGroupAlbumsDialog) {
        val albumCurrentGroupMap = remember(uiState.albumGroups) {
            uiState.albumGroups.flatMap { section ->
                section.albums.map { "album_${it.id}" to section.title }
            }.toMap()
        }

        GroupAlbumsDialog(
            allAlbums = uiState.albums,
            initialSelectedAlbumKeys = preselectedAlbumKeysForGroup,
            existingGroups = uiState.rawUserGroups,
            albumGroupMap = albumCurrentGroupMap,
            onDismissRequest = {
                showGroupAlbumsDialog = false
                preselectedAlbumKeysForGroup = emptySet()
            },
            onConfirm = { groupName, selectedKeys, existingGroupId ->
                showGroupAlbumsDialog = false
                preselectedAlbumKeysForGroup = emptySet()
                if (existingGroupId != null) {
                    viewModel.addAlbumsToGroup(existingGroupId, selectedKeys)
                } else {
                    viewModel.createUserAlbumGroup(groupName, selectedKeys)
                }
            }
        )
    }

    // Edit Group Albums Dialog
    groupToEditAlbums?.let { group ->
        EditGroupAlbumsDialog(
            groupTitle = group.name,
            allAlbums = uiState.albums,
            currentMemberAlbumKeys = group.albumKeys,
            onDismissRequest = { groupToEditAlbums = null },
            onSave = { targetKeys ->
                viewModel.updateGroupAlbums(group.id, group.albumKeys, targetKeys)
                groupToEditAlbums = null
            }
        )
    }

    // Rename User Album Group Dialog
    groupToRename?.let { group ->
        var renameText by remember(group) { mutableStateOf(group.name) }
        AlertDialog(
            onDismissRequest = { groupToRename = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            icon = { Icon(Icons.Outlined.DriveFileRenameOutline, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Rename Group") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Group Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.renameUserAlbumGroup(group.id, trimmed)
                        }
                        groupToRename = null
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dissolve Group Confirmation Dialog
    groupToDissolve?.let { group ->
        AlertDialog(
            onDismissRequest = { groupToDissolve = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            icon = { Icon(Icons.Outlined.FolderDelete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Ungroup All?") },
            text = { Text("Albums in \"${group.name}\" will be returned to the ungrouped list. Photos and videos in these albums will NOT be deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteUserAlbumGroup(group.id)
                        groupToDissolve = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Ungroup")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDissolve = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Move to Secure Folder Album selection dialog
    if (showMoveToVaultDialog) {
        MoveToVaultAlbumDialog(
            itemCount = uiState.selectedItemIds.size,
            existingAlbums = vaultAlbums,
            onDismiss = { showMoveToVaultDialog = false },
            onConfirm = { albumName ->
                showMoveToVaultDialog = false
                viewModel.moveSelectedToVault(albumName)
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Move ${uiState.selectedItemIds.size} item(s) to Recycle Bin?") },
            text = { Text("These items will be moved to the Recycle Bin. You can access and restore them back to the main gallery anytime.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteSelectedItems()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Move to Bin")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    AppUpdateDialog(
        updateState = updateState,
        onDismiss = { viewModel.dismissUpdateState() },
        onStartDownload = { info -> viewModel.downloadUpdate(info) },
        onCancelDownload = { viewModel.cancelUpdateDownload() },
        onSkipVersion = { versionName -> viewModel.skipUpdateVersion(versionName) },
        onRemindLater = { viewModel.remindMeLaterUpdate() },
        onRetry = { viewModel.checkForStartupUpdates() }
    )

    if (showDuplicateAnalyzerSheet) {
        DuplicateAnalyzerSheet(
            isScanning = duplicateScanState.isScanning,
            scannedCount = duplicateScanState.scannedCount,
            totalCount = duplicateScanState.totalCount,
            result = duplicateScanState.result,
            onDismiss = { showDuplicateAnalyzerSheet = false },
            onDeleteItem = { item -> viewModel.deleteDuplicateGalleryItem(item) },
            onDeleteAllDuplicatesInGroup = { group -> viewModel.deleteAllDuplicatesInGalleryGroup(group) },
            onRenameItem = { item, newName -> viewModel.renameDuplicateGalleryItem(item, newName) },
            thumbnail = { item, modifier ->
                MediaThumbnail(
                    item = item,
                    onClick = {},
                    modifier = modifier
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    selectedTab: GalleryTab,
    isGroupedView: Boolean,
    onVaultClick: () -> Unit,
    onRecycleBinClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCreateAlbumClick: () -> Unit,
    onToggleGroupedView: () -> Unit,
    onGroupAlbumsClick: () -> Unit,
    onFindDuplicatesClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("Gallery", fontWeight = FontWeight.SemiBold) },
        actions = {
            if (selectedTab == GalleryTab.ALBUMS) {
                IconButton(onClick = onCreateAlbumClick) {
                    Icon(Icons.Filled.Add, contentDescription = "Create Album")
                }
                IconButton(onClick = onToggleGroupedView) {
                    Icon(
                        imageVector = if (isGroupedView) Icons.Outlined.AccountTree else Icons.Outlined.GridView,
                        contentDescription = if (isGroupedView) "Switch to flat view" else "Switch to grouped view"
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f))
                ) {
                    if (selectedTab == GalleryTab.ALBUMS) {
                        DropdownMenuItem(
                            text = { Text("Group Albums") },
                            leadingIcon = { Icon(Icons.Outlined.FolderSpecial, null) },
                            onClick = {
                                showMenu = false
                                onGroupAlbumsClick()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Find Duplicates") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = {
                            showMenu = false
                            onFindDuplicatesClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Recycle Bin") },
                        leadingIcon = { Icon(Icons.Outlined.AutoDelete, null) },
                        onClick = {
                            showMenu = false
                            onRecycleBinClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Secure Vault") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                        onClick = {
                            showMenu = false
                            onVaultClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                        onClick = {
                            showMenu = false
                            onSettingsClick()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onMoveToVault: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToAlbum: () -> Unit,
    onMoveToAlbum: () -> Unit
) {
    var showMoreMenu by remember { mutableStateOf(false) }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Filled.Close, contentDescription = "Clear selection")
            }
        },
        title = {
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = "Select all")
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, contentDescription = "Share")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Box {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f))
                ) {
                    DropdownMenuItem(
                        text = { Text("Add to Favorites") },
                        leadingIcon = {
                            Icon(Icons.Outlined.FavoriteBorder, contentDescription = null)
                        },
                        onClick = {
                            showMoreMenu = false
                            onToggleFavorite()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Album") },
                        leadingIcon = {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = null)
                        },
                        onClick = {
                            showMoreMenu = false
                            onAddToAlbum()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Move to Album") },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null)
                        },
                        onClick = {
                            showMoreMenu = false
                            onMoveToAlbum()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Move to Secure Folder") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Lock, contentDescription = null)
                        },
                        onClick = {
                            showMoreMenu = false
                            onMoveToVault()
                        }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumSelectionTopBar(
    selectedCount: Int,
    selectedAlbums: List<Album>,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onMoveToVault: () -> Unit,
    onMoveToGroup: () -> Unit,
    onRenameAlbum: (Album) -> Unit,
    onChangeCover: (Album) -> Unit
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    val singleAlbum = selectedAlbums.firstOrNull().takeIf { selectedAlbums.size == 1 }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Filled.Close, contentDescription = "Clear selection")
            }
        },
        title = {
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {
            // 1. Select All Albums
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = "Select all")
            }
            // 2. Share Album
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, contentDescription = "Share")
            }
            // 3. Delete Album (tinted error)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            // 4. Overflow Actions (⋮ DropdownMenu)
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f))
                ) {
                    // Move to Group (always shown)
                    DropdownMenuItem(
                        text = { Text("Move to Group") },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null)
                        },
                        onClick = {
                            showOverflowMenu = false
                            onMoveToGroup()
                        }
                    )

                    // Move to Secure Folder (always shown)
                    DropdownMenuItem(
                        text = { Text("Move to Secure Folder") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Lock, contentDescription = null)
                        },
                        onClick = {
                            showOverflowMenu = false
                            onMoveToVault()
                        }
                    )

                    // Rename Album (shown when selectedCount == 1 and not a smart album)
                    if (singleAlbum != null && !singleAlbum.isSmartAlbum) {
                        DropdownMenuItem(
                            text = { Text("Rename Album") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Edit, contentDescription = null)
                            },
                            onClick = {
                                showOverflowMenu = false
                                onRenameAlbum(singleAlbum)
                            }
                        )
                    }

                    // Change Cover Image (shown when selectedCount == 1, not a smart album, and has at least 1 item)
                    if (singleAlbum != null && !singleAlbum.isSmartAlbum && singleAlbum.items.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Change Cover Image") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Image, contentDescription = null)
                            },
                            onClick = {
                                showOverflowMenu = false
                                onChangeCover(singleAlbum)
                            }
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun FloatingGalleryBottomNav(
    selectedTab: GalleryTab,
    onTabSelected: (GalleryTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = modifier
            .wrapContentWidth()
            .height(64.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            FloatingNavItem(
                selected = selectedTab == GalleryTab.TIMELINE,
                onClick = { onTabSelected(GalleryTab.TIMELINE) },
                icon = Icons.Outlined.PhotoLibrary,
                selectedIcon = Icons.Filled.PhotoLibrary,
                label = "Timeline"
            )
            FloatingNavItem(
                selected = selectedTab == GalleryTab.ALBUMS,
                onClick = { onTabSelected(GalleryTab.ALBUMS) },
                icon = Icons.Outlined.PhotoAlbum,
                selectedIcon = Icons.Filled.PhotoAlbum,
                label = "Albums"
            )
            FloatingNavItem(
                selected = selectedTab == GalleryTab.VIDEOS,
                onClick = { onTabSelected(GalleryTab.VIDEOS) },
                icon = Icons.Outlined.Videocam,
                selectedIcon = Icons.Filled.Videocam,
                label = "Videos"
            )
        }
    }
}

@Composable
private fun FloatingNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    selectedIcon: ImageVector,
    label: String
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navContentColor"
    )
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "navIndicatorColor"
    )

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(indicatorColor)
                    .padding(horizontal = 16.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (selected) selectedIcon else icon,
                    contentDescription = label,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 10.5.sp
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineGrid(
    mediaGroups: List<MediaGroup>,
    monthlyMediaGroups: List<MediaGroup>,
    columns: Int,
    onColumnsChanged: (Int) -> Unit,
    savedScroll: GridScrollPosition = GridScrollPosition(),
    onSaveScrollPosition: (Int, Int) -> Unit = { _, _ -> },
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    onItemClick: (MediaItem) -> Unit,
    onItemLongClick: (MediaItem) -> Unit,
    isParentScrolling: () -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    if (mediaGroups.isEmpty()) {
        EmptyStateView(
            icon = Icons.Outlined.PhotoLibrary,
            title = "No Photos Yet",
            subtitle = "Photos and videos from your device will appear here",
            modifier = modifier
        )
        return
    }

    val gridState = rememberViewportOnlyLazyGridState(
        initialFirstVisibleItemIndex = savedScroll.index,
        initialFirstVisibleItemScrollOffset = savedScroll.offset
    )

    // Safety net: restore scroll if mediaGroups populate after initial frame
    LaunchedEffect(mediaGroups.isNotEmpty()) {
        if (mediaGroups.isNotEmpty() && savedScroll.index > 0 && gridState.firstVisibleItemIndex == 0) {
            gridState.scrollToItem(savedScroll.index, savedScroll.offset)
        }
    }

    AnimatedPinchGridContainer(
        currentColumns = columns,
        onColumnsChanged = onColumnsChanged,
        minColumns = 2,
        maxColumns = 7,
        gridState = gridState,
        initialFirstVisibleItemIndex = savedScroll.index,
        initialFirstVisibleItemScrollOffset = savedScroll.offset,
        onScrollPositionChanged = onSaveScrollPosition,
        onResolveTargetAnchorIndex = { sourceCols, targetCols, sourceFlatIndex ->
            val srcGroups = if (sourceCols >= 5) monthlyMediaGroups else mediaGroups
            val dstGroups = if (targetCols >= 5) monthlyMediaGroups else mediaGroups
            resolveTimelineAnchorIndex(srcGroups, dstGroups, sourceFlatIndex)
        },
        modifier = modifier.fillMaxSize(),
        overlayContent = { activeGridState, activeColumns ->
            val activeGroups = if (activeColumns >= 5) monthlyMediaGroups else mediaGroups
            val flatHeaders = remember(activeGroups) {
                val list = mutableListOf<String>()
                activeGroups.forEach { group ->
                    list.add(group.dateLabel)
                    group.items.forEach { _ ->
                        list.add(group.dateLabel)
                    }
                }
                list
            }
            VerticalFastScrollbar(
                gridState = activeGridState,
                modifier = Modifier.fillMaxSize(),
                labelProvider = { index ->
                    if (index in flatHeaders.indices) flatHeaders[index] else ""
                }
            )
        }
    ) { layerColumns, layerGridState, isPreviewLayer, tileAspectRatio ->
        val isMonthlyOverview = layerColumns >= 5
        val layerGroups = if (isMonthlyOverview) monthlyMediaGroups else mediaGroups
        val headerKeyPrefix = if (isMonthlyOverview) "header_m_" else "header_d_"
        val targetSize = gridThumbnailTargetSize(layerColumns)
        val isLayerScrolling by remember(layerGridState) {
            derivedStateOf { layerGridState.isScrollInProgress || isParentScrolling() }
        }
        val effectiveScrolling = if (isPreviewLayer) false else isLayerScrolling

        LazyVerticalGrid(
            state = layerGridState,
            columns = GridCells.Fixed(layerColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 2.dp, top = 2.dp, end = 2.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            layerGroups.forEach { group ->
                item(
                    key = "$headerKeyPrefix${group.dateLabel}",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    DateGroupHeader(
                        label = group.dateLabel,
                        count = group.items.size,
                        isCompact = isMonthlyOverview,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                items(
                    items = group.items,
                    key = { "media_${it.id}" }
                ) { mediaItem ->
                    MediaThumbnail(
                        item = mediaItem,
                        onClick = {
                            onSaveScrollPosition(layerGridState.firstVisibleItemIndex, layerGridState.firstVisibleItemScrollOffset)
                            onItemClick(mediaItem)
                        },
                        onLongClick = { onItemLongClick(mediaItem) },
                        isSelectionMode = isSelectionMode,
                        isSelected = mediaItem.id in selectedIds,
                        isScrolling = effectiveScrolling,
                        forceLoad = isPreviewLayer,
                        targetSize = targetSize,
                        modifier = Modifier.aspectRatio(tileAspectRatio)
                    )
                }
            }
        }
    }
}

private fun resolveTimelineAnchorIndex(
    sourceGroups: List<MediaGroup>,
    targetGroups: List<MediaGroup>,
    sourceFlatIndex: Int
): Int {
    if (sourceGroups === targetGroups || sourceGroups.isEmpty() || targetGroups.isEmpty()) {
        return sourceFlatIndex.coerceAtLeast(0)
    }
    var cursor = 0
    var anchorMediaId: Long? = null
    for (group in sourceGroups) {
        if (sourceFlatIndex == cursor) {
            anchorMediaId = group.items.firstOrNull()?.id
            break
        }
        cursor++ // section header
        val groupEnd = cursor + group.items.size
        if (sourceFlatIndex < groupEnd) {
            anchorMediaId = group.items[sourceFlatIndex - cursor].id
            break
        }
        cursor = groupEnd
    }
    val targetId = anchorMediaId ?: return sourceFlatIndex.coerceAtLeast(0)

    var targetCursor = 0
    for (group in targetGroups) {
        targetCursor++ // section header
        for (i in group.items.indices) {
            if (group.items[i].id == targetId) {
                return targetCursor + i
            }
        }
        targetCursor += group.items.size
    }
    return sourceFlatIndex.coerceAtLeast(0)
}

@Composable
private fun DateGroupHeader(
    label: String,
    count: Int,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = if (isCompact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count items",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumsGrid(
    albums: List<Album>,
    userAlbumGroups: List<UserAlbumGroupUiModel>,
    ungroupedAlbums: List<Album>,
    albumGroups: List<AlbumGroupSection>,
    isGroupedView: Boolean,
    collapsedGroups: Set<String>,
    savedScroll: GridScrollPosition = GridScrollPosition(),
    onSaveScrollPosition: (Int, Int) -> Unit = { _, _ -> },
    isAlbumSelectionMode: Boolean = false,
    selectedAlbumIds: Set<Long> = emptySet(),
    onToggleGroupCollapsed: (String) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onAlbumLongClick: (Album) -> Unit,
    onEditGroupAlbums: (UserAlbumGroupUiModel) -> Unit,
    onRenameGroup: (UserAlbumGroupUiModel) -> Unit,
    onDissolveGroup: (UserAlbumGroupUiModel) -> Unit,
    isParentScrolling: () -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    if (albums.isEmpty()) {
        EmptyStateView(
            icon = Icons.Outlined.PhotoAlbum,
            title = "No Albums",
            subtitle = "Albums from your device folders will appear here",
            modifier = modifier
        )
        return
    }

    val gridState = rememberViewportOnlyLazyGridState(
        initialFirstVisibleItemIndex = savedScroll.index,
        initialFirstVisibleItemScrollOffset = savedScroll.offset
    )

    // Safety net: restore scroll if albums load after initial frame
    LaunchedEffect(albums.isNotEmpty()) {
        if (albums.isNotEmpty() && savedScroll.index > 0 && gridState.firstVisibleItemIndex == 0) {
            gridState.scrollToItem(savedScroll.index, savedScroll.offset)
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow {
            Triple(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
                gridState.isScrollInProgress
            )
        }.collect { (idx, off, isScrolling) ->
            if (isScrolling || idx > 0 || off > 0) {
                onSaveScrollPosition(idx, off)
            }
        }
    }

    val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress || isParentScrolling() } }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isGroupedView) {
                val standardGroupTitles = setOf("Smart Albums", "My Albums", "Camera & Device", "Social & Apps", "Other Albums")

                albumGroups.forEach { section ->
                    val isCustomUserGroup = section.title !in standardGroupTitles
                    val matchingUserGroup = if (isCustomUserGroup) {
                        userAlbumGroups.find { it.name.equals(section.title, ignoreCase = true) }
                            ?: UserAlbumGroupUiModel(
                                id = section.title.hashCode().toLong(),
                                name = section.title,
                                albums = section.albums
                            )
                    } else null

                    val isCollapsed = section.title in collapsedGroups ||
                        (matchingUserGroup != null && "user_group_${matchingUserGroup.id}" in collapsedGroups)

                    if (matchingUserGroup != null) {
                        item(
                            key = "header_user_group_${section.title}",
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            UserAlbumGroupHeader(
                                group = matchingUserGroup.copy(albums = section.albums),
                                isCollapsed = isCollapsed,
                                onToggleCollapse = { onToggleGroupCollapsed(section.title) },
                                onEditAlbums = { onEditGroupAlbums(matchingUserGroup.copy(albums = section.albums)) },
                                onRename = { onRenameGroup(matchingUserGroup.copy(albums = section.albums)) },
                                onDissolve = { onDissolveGroup(matchingUserGroup.copy(albums = section.albums)) }
                            )
                        }
                    } else {
                        item(
                            key = "header_group_${section.title}",
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            AlbumGroupHeader(
                                title = section.title,
                                count = section.albums.size,
                                isCollapsed = isCollapsed,
                                onToggleCollapse = { onToggleGroupCollapsed(section.title) }
                            )
                        }
                    }

                    if (!isCollapsed) {
                        items(section.albums, key = { "group_${section.title}_album_${it.id}" }) { album ->
                            AlbumCard(
                                album = album,
                                onClick = {
                                    onSaveScrollPosition(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                    onAlbumClick(album)
                                },
                                onLongClick = { onAlbumLongClick(album) },
                                isScrolling = isScrolling,
                                isSelectionMode = isAlbumSelectionMode,
                                isSelected = album.id in selectedAlbumIds
                            )
                        }
                    }
                }
            } else {
                // Flat View: Ungrouped 2-column grid
                items(albums, key = { "flat_album_${it.id}" }) { album ->
                    AlbumCard(
                        album = album,
                        onClick = {
                            onSaveScrollPosition(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                            onAlbumClick(album)
                        },
                        onLongClick = { onAlbumLongClick(album) },
                        isScrolling = isScrolling,
                        isSelectionMode = isAlbumSelectionMode,
                        isSelected = album.id in selectedAlbumIds
                    )
                }
            }
        }

        VerticalFastScrollbar(
            gridState = gridState,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun UserAlbumGroupHeader(
    group: UserAlbumGroupUiModel,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onEditAlbums: () -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggleCollapse)
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isCollapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore,
                contentDescription = if (isCollapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${group.albums.size} albums • ${group.totalMediaCount} items",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "Group actions",
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f))
                ) {
                    DropdownMenuItem(
                        text = { Text("Add / Remove Albums") },
                        leadingIcon = { Icon(Icons.Outlined.FolderCopy, null) },
                        onClick = {
                            showMenu = false
                            onEditAlbums()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename Group") },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Ungroup All") },
                        leadingIcon = { Icon(Icons.Outlined.FolderDelete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDissolve()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumGroupHeader(
    title: String,
    count: Int,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggleCollapse)
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$count album${if (count != 1) "s" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (isCollapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (isCollapsed) "Expand group" else "Collapse group",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isScrolling: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (isSelectionMode && isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            val context = LocalContext.current
            val cacheKey = "album_cover_${album.id}_${album.coverUri}"
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
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                if (album.itemCount > 0 && album.coverUri != Uri.EMPTY) {
                    if (shouldLoad) {
                        val albumThumbRequest = remember(album.id, album.coverUri) {
                            coil.request.ImageRequest.Builder(context)
                                .data(album.coverUri)
                                .size(180, 180)
                                .precision(coil.size.Precision.INEXACT)
                                .scale(coil.size.Scale.FILL)
                                .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                                .allowHardware(true)
                                .memoryCacheKey(cacheKey)
                                .diskCacheKey(cacheKey)
                                .crossfade(false)
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                .build()
                        }
                        coil.compose.AsyncImage(
                            model = albumThumbRequest,
                            contentDescription = album.name,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (album.isSmartAlbum) Icons.Filled.Favorite else Icons.Outlined.PhotoAlbum,
                            contentDescription = null,
                            tint = if (album.isSmartAlbum) Color(0xFFFF3366) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }

                // Selection indicator (scrim + top-end circle)
                if (isSelectionMode) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(26.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(4.dp)
                                )
                            }
                        } else {
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.35f),
                                border = BorderStroke(2.dp, Color.White),
                                modifier = Modifier.fillMaxSize()
                            ) {}
                        }
                    }
                }

                // Smart Album badge (heart for Favorites)
                if (!isSelectionMode && album.isSmartAlbum) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .padding(5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "Smart Album",
                            tint = Color(0xFFFF3366),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                } else if (!isSelectionMode && album.isCustom) {
                    // Custom Album badge
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Custom",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = "${album.itemCount} items",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideosGrid(
    videos: List<MediaItem.Video>,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    onVideoClick: (MediaItem.Video) -> Unit,
    onVideoLongClick: (MediaItem.Video) -> Unit,
    columns: Int = 3,
    onColumnsChanged: (Int) -> Unit = {},
    savedScroll: GridScrollPosition = GridScrollPosition(),
    onSaveScrollPosition: (Int, Int) -> Unit = { _, _ -> },
    isParentScrolling: () -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    if (videos.isEmpty()) {
        EmptyStateView(
            icon = Icons.Outlined.Videocam,
            title = "No Videos",
            subtitle = "Videos from your device will appear here",
            modifier = modifier
        )
        return
    }

    val gridState = rememberViewportOnlyLazyGridState(
        initialFirstVisibleItemIndex = savedScroll.index,
        initialFirstVisibleItemScrollOffset = savedScroll.offset
    )

    // Safety net: restore scroll if videos load after initial frame
    LaunchedEffect(videos.isNotEmpty()) {
        if (videos.isNotEmpty() && savedScroll.index > 0 && gridState.firstVisibleItemIndex == 0) {
            gridState.scrollToItem(savedScroll.index, savedScroll.offset)
        }
    }

    AnimatedPinchGridContainer(
        currentColumns = columns,
        onColumnsChanged = onColumnsChanged,
        minColumns = 2,
        maxColumns = 7,
        gridState = gridState,
        initialFirstVisibleItemIndex = savedScroll.index,
        initialFirstVisibleItemScrollOffset = savedScroll.offset,
        onScrollPositionChanged = onSaveScrollPosition,
        modifier = modifier.fillMaxSize(),
        overlayContent = { activeGridState, _ ->
            VerticalFastScrollbar(
                gridState = activeGridState,
                modifier = Modifier.fillMaxSize()
            )
        }
    ) { layerColumns, layerGridState, isPreviewLayer, tileAspectRatio ->
        val targetSize = gridThumbnailTargetSize(layerColumns)
        val isLayerScrolling by remember(layerGridState) {
            derivedStateOf { layerGridState.isScrollInProgress || isParentScrolling() }
        }
        val effectiveScrolling = if (isPreviewLayer) false else isLayerScrolling

        LazyVerticalGrid(
            state = layerGridState,
            columns = GridCells.Fixed(layerColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 2.dp, top = 2.dp, end = 2.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(
                items = videos,
                key = { "video_${it.id}" }
            ) { video ->
                MediaThumbnail(
                    item = video,
                    onClick = {
                        onSaveScrollPosition(layerGridState.firstVisibleItemIndex, layerGridState.firstVisibleItemScrollOffset)
                        onVideoClick(video)
                    },
                    onLongClick = { onVideoLongClick(video) },
                    isSelectionMode = isSelectionMode,
                    isSelected = video.id in selectedIds,
                    isScrolling = effectiveScrolling,
                    forceLoad = isPreviewLayer,
                    targetSize = targetSize,
                    modifier = Modifier.aspectRatio(tileAspectRatio)
                )
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Allow Photos Access",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "To view and manage your gallery, please allow access to your photos and videos.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRequestPermission,
            shape = MaterialTheme.shapes.medium
        ) {
            Text("Grant Permission")
        }
    }
}
