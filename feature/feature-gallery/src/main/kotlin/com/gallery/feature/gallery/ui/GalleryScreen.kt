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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.memory.MemoryCache
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import com.gallery.core.domain.model.Album
import com.gallery.core.domain.model.AppUpdateState
import com.gallery.core.domain.model.MediaItem
import com.gallery.core.ui.components.AddToAlbumDialog
import com.gallery.core.ui.components.AppUpdateDialog
import com.gallery.core.ui.components.ChangeAlbumCoverDialog
import com.gallery.core.ui.components.CreateAlbumDialog
import com.gallery.core.ui.components.EditGroupAlbumsDialog
import com.gallery.core.ui.components.EmptyStateView
import com.gallery.core.ui.components.GroupAlbumsDialog
import com.gallery.core.ui.components.MediaThumbnail
import com.gallery.core.ui.components.MoveAlbumToGroupDialog
import com.gallery.core.ui.components.MoveAlbumToVaultDialog
import com.gallery.core.ui.components.MoveToAlbumDialog
import com.gallery.core.ui.components.MoveToGroupDialog
import com.gallery.core.ui.components.MoveToVaultAlbumDialog
import com.gallery.core.ui.components.RenameAlbumDialog
import com.gallery.core.ui.components.VerticalFastScrollbar
import com.gallery.feature.gallery.viewmodel.AlbumGroupSection
import com.gallery.feature.gallery.viewmodel.GalleryTab
import com.gallery.feature.gallery.viewmodel.GalleryUiState
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
            onToggleFavoritesForSelected = { viewModel.toggleFavoritesForSelected() },
            onAddSelectedToCustomAlbum = { targetAlbumId, _ -> viewModel.addSelectedToCustomAlbum(targetAlbumId) },
            onCreateAndAddSelectedToCustomAlbum = { name, _ -> viewModel.createAndAddSelectedToCustomAlbum(name) },
            onRemoveSelectedFromAlbum = { albumId, items -> viewModel.removeSelectedFromCustomAlbum(albumId, items) },
            onRenameAlbum = { albumId, newName -> viewModel.renameCustomAlbum(albumId, newName) },
            onDeleteAlbum = { albumId -> viewModel.deleteCustomAlbum(albumId) },
            onAddItemsToAlbum = { albumId, items -> viewModel.addItemsToCustomAlbum(albumId, items) },
            columnCount = gridColumns
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
    ) { _ ->
        // When user grants system deletion, MediaStore automatically updates via ContentObserver
    }

    LaunchedEffect(uiState.pendingDeleteIntentSender) {
        uiState.pendingDeleteIntentSender?.let { sender ->
            systemDeleteLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(sender).build()
            )
            viewModel.clearPendingDeleteIntent()
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
                        beyondViewportPageCount = 1
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
                                    columns = gridColumns,
                                    isSelectionMode = uiState.isSelectionMode,
                                    selectedIds = uiState.selectedItemIds,
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
                                    isAlbumSelectionMode = uiState.isAlbumSelectionMode,
                                    selectedAlbumIds = uiState.selectedAlbumIds,
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
                                    isSelectionMode = uiState.isSelectionMode,
                                    selectedIds = uiState.selectedItemIds,
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
        onSkipVersion = { versionCode -> viewModel.skipUpdateVersion(versionCode) },
        onRetry = { viewModel.checkForStartupUpdates() }
    )
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
    onGroupAlbumsClick: () -> Unit
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
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
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
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Filled.Close, contentDescription = "Exit selection")
            }
        },
        title = {
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = "Select / Deselect All")
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = Icons.Outlined.Favorite,
                    contentDescription = "Favorite",
                    tint = Color(0xFFFF3366)
                )
            }
            IconButton(onClick = onAddToAlbum) {
                Icon(Icons.Outlined.DriveFolderUpload, contentDescription = "Add to Album")
            }
            IconButton(onClick = onMoveToAlbum) {
                Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = "Move to Album")
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, contentDescription = "Share")
            }
            IconButton(onClick = onMoveToVault) {
                Icon(Icons.Outlined.Lock, contentDescription = "Move to Secure Folder")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
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
                Icon(Icons.Filled.Close, contentDescription = "Exit album selection")
            }
        },
        title = {
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        actions = {
            // 1. Select All Albums
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = "Select / Deselect All")
            }
            // 2. Share Album
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, contentDescription = "Share Album")
            }
            // 3. Move to Vault
            IconButton(onClick = onMoveToVault) {
                Icon(Icons.Outlined.Lock, contentDescription = "Move to Secure Vault")
            }
            // 4. Delete Album (tinted error)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete Album",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            // 5. Overflow Actions (⋮ DropdownMenu)
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false }
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

                    // Change Cover Image (shown when selectedCount == 1 and album has at least 1 item)
                    if (singleAlbum != null && singleAlbum.items.isNotEmpty()) {
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
    columns: Int,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    onItemClick: (MediaItem) -> Unit,
    onItemLongClick: (MediaItem) -> Unit,
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

    val gridState = rememberLazyGridState()
    val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress } }

    val flatHeaders = remember(mediaGroups) {
        val list = mutableListOf<String>()
        mediaGroups.forEach { group ->
            list.add(group.dateLabel)
            group.items.forEach { _ ->
                list.add(group.dateLabel)
            }
        }
        list
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 2.dp, top = 2.dp, end = 2.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            mediaGroups.forEach { group ->
                item(
                    key = "header_${group.dateLabel}",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    DateGroupHeader(
                        label = group.dateLabel,
                        count = group.items.size,
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                    )
                }

                items(
                    items = group.items,
                    key = { "media_${it.id}" }
                ) { mediaItem ->
                    MediaThumbnail(
                        item = mediaItem,
                        onClick = { onItemClick(mediaItem) },
                        onLongClick = { onItemLongClick(mediaItem) },
                        isSelectionMode = isSelectionMode,
                        isSelected = mediaItem.id in selectedIds,
                        isScrolling = isScrolling,
                        targetSize = 160,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .animateItem()
                    )
                }
            }
        }

        VerticalFastScrollbar(
            gridState = gridState,
            modifier = Modifier.fillMaxSize(),
            labelProvider = { index ->
                if (index in flatHeaders.indices) flatHeaders[index] else ""
            }
        )
    }
}

@Composable
private fun DateGroupHeader(
    label: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
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

@Composable
private fun AlbumsGrid(
    albums: List<Album>,
    userAlbumGroups: List<UserAlbumGroupUiModel>,
    ungroupedAlbums: List<Album>,
    albumGroups: List<AlbumGroupSection>,
    isGroupedView: Boolean,
    collapsedGroups: Set<String>,
    isAlbumSelectionMode: Boolean = false,
    selectedAlbumIds: Set<Long> = emptySet(),
    onToggleGroupCollapsed: (String) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onAlbumLongClick: (Album) -> Unit,
    onEditGroupAlbums: (UserAlbumGroupUiModel) -> Unit,
    onRenameGroup: (UserAlbumGroupUiModel) -> Unit,
    onDissolveGroup: (UserAlbumGroupUiModel) -> Unit,
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

    val gridState = rememberLazyGridState()
    val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress } }

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
                                onClick = { onAlbumClick(album) },
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
                        onClick = { onAlbumClick(album) },
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
                    onDismissRequest = { showMenu = false }
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
            val cacheKey = "thumb_${album.id}_${album.coverUri}"
            val imageLoader = context.imageLoader
            val isInMemory = remember(cacheKey) {
                imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(cacheKey)) != null
            }
            var shouldLoad by remember(cacheKey) {
                mutableStateOf(isInMemory || !isScrolling)
            }
            LaunchedEffect(isScrolling) {
                if (!isScrolling) {
                    shouldLoad = true
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
                                .crossfade(150)
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

@Composable
private fun VideosGrid(
    videos: List<MediaItem.Video>,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    onVideoClick: (MediaItem.Video) -> Unit,
    onVideoLongClick: (MediaItem.Video) -> Unit,
    columns: Int = 3,
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

    val gridState = rememberLazyGridState()
    val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress } }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
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
                    onClick = { onVideoClick(video) },
                    onLongClick = { onVideoLongClick(video) },
                    isSelectionMode = isSelectionMode,
                    isSelected = video.id in selectedIds,
                    isScrolling = isScrolling,
                    targetSize = if (columns <= 2) 180 else 160,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .animateItem()
                )
            }
        }

        VerticalFastScrollbar(
            gridState = gridState,
            modifier = Modifier.fillMaxSize()
        )
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
