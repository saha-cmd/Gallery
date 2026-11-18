package com.gallery.feature.gallery.ui

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gallery.core.domain.model.Album
import com.gallery.core.domain.model.MediaItem
import com.gallery.core.ui.components.AddToAlbumDialog
import com.gallery.core.ui.components.CreateAlbumDialog
import com.gallery.core.ui.components.EmptyStateView
import com.gallery.core.ui.components.MediaThumbnail
import com.gallery.core.ui.components.MoveToAlbumDialog
import com.gallery.core.ui.components.MoveToVaultAlbumDialog
import com.gallery.core.ui.components.VerticalFastScrollbar
import com.gallery.core.ui.components.pinchToZoomGrid

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(
    album: Album,
    onBack: () -> Unit,
    onMediaClick: (Long, Boolean) -> Unit,
    onMoveItemsToVault: (List<MediaItem>, String) -> Unit = { _, _ -> },
    onDeleteItemsToRecycleBin: (List<MediaItem>) -> Unit = {},
    vaultAlbums: List<String> = emptyList(),
    allMedia: List<MediaItem> = emptyList(),
    customAlbums: List<Album> = emptyList(),
    onToggleFavoritesForSelected: (List<MediaItem>) -> Unit = {},
    onAddSelectedToCustomAlbum: (Long, List<MediaItem>) -> Unit = { _, _ -> },
    onCreateAndAddSelectedToCustomAlbum: (String, List<MediaItem>) -> Unit = { _, _ -> },
    onRemoveSelectedFromAlbum: (Long, List<MediaItem>) -> Unit = { _, _ -> },
    onRenameAlbum: (Long, String) -> Unit = { _, _ -> },
    onDeleteAlbum: (Long) -> Unit = {},
    onAddItemsToAlbum: (Long, List<MediaItem>) -> Unit = { _, _ -> },
    albums: List<Album> = emptyList(),
    onMoveItemsToAlbum: (List<MediaItem>, Album?, String?) -> Unit = { _, _, _ -> },
    pendingMoveIntentSender: IntentSender? = null,
    onRetryMoveAfterPermission: () -> Unit = {},
    onClearPendingMoveIntent: () -> Unit = {},
    columnCount: Int = 3,
    onColumnCountChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var showMoveToVaultDialog by remember { mutableStateOf(false) }
    var showMoveToAlbumDialog by remember { mutableStateOf(false) }
    var showAddToAlbumDialog by remember { mutableStateOf(false) }
    var showAddItemsSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteAlbumDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val isSelectionMode = selectedIds.isNotEmpty()

    val movePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onRetryMoveAfterPermission()
        } else {
            onClearPendingMoveIntent()
        }
    }

    LaunchedEffect(pendingMoveIntentSender) {
        pendingMoveIntentSender?.let { sender ->
            movePermissionLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    BackHandler(enabled = isSelectionMode) {
        selectedIds = emptySet()
    }
    BackHandler(enabled = !isSelectionMode) {
        onBack()
    }

    val selectedItems = remember(selectedIds, album.items) {
        album.items.filter { it.id in selectedIds }
    }

    fun shareSelected() {
        if (selectedItems.isEmpty()) return
        val uris = ArrayList(selectedItems.map { it.uri })
        val shareIntent = Intent().apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            type = if (selectedItems.all { it is MediaItem.Photo }) "image/*"
            else if (selectedItems.all { it is MediaItem.Video }) "video/*"
            else "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share ${selectedItems.size} items"))
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Filled.Close, "Exit selection")
                        }
                    },
                    title = {
                        Text(
                            text = "${selectedIds.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        IconButton(onClick = {
                            val allIds = album.items.map { it.id }.toSet()
                            selectedIds = if (selectedIds.containsAll(allIds)) emptySet() else allIds
                        }) {
                            Icon(Icons.Outlined.SelectAll, "Select all")
                        }
                        // Batch Favorite action
                        IconButton(onClick = {
                            onToggleFavoritesForSelected(selectedItems)
                            selectedIds = emptySet()
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Favorite,
                                contentDescription = "Favorite",
                                tint = Color(0xFFFF3366)
                            )
                        }
                        // Batch Add to Album action
                        IconButton(onClick = { showAddToAlbumDialog = true }) {
                            Icon(Icons.Outlined.DriveFolderUpload, "Add to Album")
                        }
                        // Batch Move to Album action
                        IconButton(onClick = { showMoveToAlbumDialog = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.DriveFileMove,
                                contentDescription = "Move to Album"
                            )
                        }
                        IconButton(onClick = ::shareSelected) {
                            Icon(Icons.Outlined.Share, "Share")
                        }
                        IconButton(onClick = { showMoveToVaultDialog = true }) {
                            Icon(Icons.Outlined.Lock, "Move to Secure Folder")
                        }
                        // If custom album, provide Remove from Album action
                        if (album.isCustom) {
                            IconButton(onClick = {
                                onRemoveSelectedFromAlbum(album.id, selectedItems)
                                selectedIds = emptySet()
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.FolderDelete,
                                    contentDescription = "Remove from Album",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(onClick = {
                            onDeleteItemsToRecycleBin(selectedItems)
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Outlined.Delete, "Delete to Recycle Bin", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                        }
                    },
                    title = {
                        Column {
                            Text(album.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                text = "${album.itemCount} items${if (album.isCustom) " · Custom Album" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        // Custom Album actions: Add Items, Rename, Delete
                        if (album.isCustom) {
                            IconButton(onClick = { showAddItemsSheet = true }) {
                                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Add Items to Album")
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "Album options")
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Add Items") },
                                        leadingIcon = { Icon(Icons.Outlined.Add, null) },
                                        onClick = {
                                            showMenu = false
                                            showAddItemsSheet = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename Album") },
                                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                        onClick = {
                                            showMenu = false
                                            showRenameDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete Album") },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showMenu = false
                                            showDeleteAlbumDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            if (album.items.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Outlined.PhotoAlbum,
                    title = "Album is Empty",
                    subtitle = if (album.isCustom) "Tap '+' above to add photos and videos to this album" else "No photos or videos found in this folder"
                )
            } else {
                val gridState = rememberLazyGridState(
                    prefetchStrategy = remember { LazyGridPrefetchStrategy(nestedPrefetchItemCount = 0) }
                )
                val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress } }
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(columnCount),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .pinchToZoomGrid(
                                currentColumns = columnCount,
                                gridState = gridState,
                                onColumnsChanged = onColumnCountChanged
                            )
                    ) {
                        items(album.items, key = { it.id }) { mediaItem ->
                            MediaThumbnail(
                                item = mediaItem,
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedIds = if (mediaItem.id in selectedIds) selectedIds - mediaItem.id else selectedIds + mediaItem.id
                                    } else {
                                        onMediaClick(mediaItem.id, mediaItem is MediaItem.Video)
                                    }
                                },
                                onLongClick = {
                                    selectedIds = if (mediaItem.id in selectedIds) selectedIds - mediaItem.id else selectedIds + mediaItem.id
                                },
                                isSelectionMode = isSelectionMode,
                                isSelected = mediaItem.id in selectedIds,
                                isScrolling = isScrolling,
                                targetSize = if (columnCount <= 2) 200 else 160,
                                modifier = Modifier.aspectRatio(1f)
                            )
                        }
                    }

                    VerticalFastScrollbar(
                        gridState = gridState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // Move to Vault Dialog
    if (showMoveToVaultDialog) {
        MoveToVaultAlbumDialog(
            itemCount = selectedIds.size,
            existingAlbums = vaultAlbums,
            onDismiss = { showMoveToVaultDialog = false },
            onConfirm = { albumName ->
                showMoveToVaultDialog = false
                onMoveItemsToVault(selectedItems, albumName)
                selectedIds = emptySet()
            }
        )
    }

    // Move to Album Dialog (for batch selection in AlbumDetailScreen)
    if (showMoveToAlbumDialog) {
        MoveToAlbumDialog(
            itemCount = selectedIds.size,
            albums = albums,
            currentAlbumId = album.id,
            onDismiss = { showMoveToAlbumDialog = false },
            onConfirm = { targetAlbum, newAlbumName ->
                showMoveToAlbumDialog = false
                onMoveItemsToAlbum(selectedItems, targetAlbum, newAlbumName)
                selectedIds = emptySet()
            }
        )
    }

    // Add to Album Dialog (for batch selection in AlbumDetailScreen)
    if (showAddToAlbumDialog) {
        AddToAlbumDialog(
            customAlbums = customAlbums,
            onDismissRequest = { showAddToAlbumDialog = false },
            onSelectAlbum = { targetAlbumId ->
                showAddToAlbumDialog = false
                onAddSelectedToCustomAlbum(targetAlbumId, selectedItems)
                selectedIds = emptySet()
            },
            onCreateNewAlbum = { newName ->
                showAddToAlbumDialog = false
                onCreateAndAddSelectedToCustomAlbum(newName, selectedItems)
                selectedIds = emptySet()
            },
            itemCount = selectedItems.size
        )
    }

    // Rename Custom Album Dialog
    if (showRenameDialog) {
        CreateAlbumDialog(
            title = "Rename Album",
            initialName = album.name,
            confirmButtonText = "Rename",
            onDismissRequest = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                onRenameAlbum(album.id, newName)
            }
        )
    }

    // Delete Custom Album Dialog
    if (showDeleteAlbumDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAlbumDialog = false },
            icon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Album?") },
            text = { Text("\"${album.name}\" will be deleted. Photos and videos in this album will NOT be deleted from your device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAlbumDialog = false
                        onDeleteAlbum(album.id)
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAlbumDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add Items to Custom Album Multi-Select Sheet
    if (showAddItemsSheet) {
        val existingIds = remember(album.items) { album.items.map { it.id }.toSet() }
        val availableMedia = remember(allMedia, existingIds) {
            allMedia.filter { it.id !in existingIds }
        }
        var sheetSelectedIds by remember { mutableStateOf(emptySet<Long>()) }

        ModalBottomSheet(
            onDismissRequest = { showAddItemsSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Add to \"${album.name}\"",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (sheetSelectedIds.isEmpty()) "Select photos & videos" else "${sheetSelectedIds.size} selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            val itemsToAdd = availableMedia.filter { it.id in sheetSelectedIds }
                            onAddItemsToAlbum(album.id, itemsToAdd)
                            showAddItemsSheet = false
                        },
                        enabled = sheetSelectedIds.isNotEmpty()
                    ) {
                        Text("Add")
                    }
                }

                if (availableMedia.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "All photos and videos are already in this album",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val sheetGridState = rememberLazyGridState(
                        prefetchStrategy = remember { LazyGridPrefetchStrategy(nestedPrefetchItemCount = 0) }
                    )
                    val isSheetScrolling by remember { derivedStateOf { sheetGridState.isScrollInProgress } }
                    LazyVerticalGrid(
                        state = sheetGridState,
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                    ) {
                        items(availableMedia, key = { it.id }) { item ->
                            val isSelected = item.id in sheetSelectedIds
                            MediaThumbnail(
                                item = item,
                                onClick = {
                                    sheetSelectedIds = if (isSelected) sheetSelectedIds - item.id else sheetSelectedIds + item.id
                                },
                                isSelectionMode = true,
                                isSelected = isSelected,
                                isScrolling = isSheetScrolling,
                                targetSize = 160,
                                modifier = Modifier.aspectRatio(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
