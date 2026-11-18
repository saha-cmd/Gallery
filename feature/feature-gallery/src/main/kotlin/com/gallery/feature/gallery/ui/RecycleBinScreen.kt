package com.gallery.feature.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import android.graphics.Bitmap
import com.gallery.core.domain.model.RecycleBinItem
import com.gallery.core.ui.components.AnimatedPinchGridContainer
import com.gallery.core.ui.components.EmptyStateView
import com.gallery.core.ui.components.VerticalFastScrollbar
import com.gallery.core.ui.components.gridThumbnailTargetSize
import com.gallery.feature.gallery.viewmodel.RecycleBinViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecycleBinScreen(
    onBack: () -> Unit,
    viewModel: RecycleBinViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gridColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    var showEmptyBinDialog by remember { mutableStateOf(false) }
    var itemToDeletePermanently by remember { mutableStateOf<RecycleBinItem?>(null) }
    var itemForActionSheet by remember { mutableStateOf<RecycleBinItem?>(null) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    val deleteIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onSystemDeleteConfirmed()
        } else {
            viewModel.onSystemDeleteCancelled()
        }
    }

    LaunchedEffect(uiState.pendingDeleteIntentSender) {
        uiState.pendingDeleteIntentSender?.let { sender ->
            deleteIntentLauncher.launch(
                IntentSenderRequest.Builder(sender).build()
            )
        }
    }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }
    BackHandler(enabled = !uiState.isSelectionMode) {
        onBack()
    }

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, "Exit selection")
                        }
                    },
                    title = {
                        Text(
                            text = "${uiState.selectedItemIds.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(Icons.Outlined.SelectAll, "Select all")
                        }
                        IconButton(onClick = viewModel::restoreSelected) {
                            Icon(Icons.Outlined.RestoreFromTrash, "Restore")
                        }
                        IconButton(onClick = { showDeleteSelectedDialog = true }) {
                            Icon(Icons.Outlined.DeleteForever, "Delete Permanently", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("Recycle Bin", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "${uiState.items.size} deleted items",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        if (uiState.items.isNotEmpty()) {
                            TextButton(
                                onClick = { showEmptyBinDialog = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Empty")
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
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            when {
                uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.items.isEmpty() -> {
                    EmptyStateView(
                        icon = Icons.Outlined.Delete,
                        title = "Recycle Bin is Empty",
                        subtitle = "Deleted photos and videos stay here and can be restored back to their original albums and timeline positions anytime"
                    )
                }
                else -> {
                    AnimatedPinchGridContainer(
                        currentColumns = gridColumns,
                        onColumnsChanged = viewModel::setGridColumns,
                        minColumns = 2,
                        maxColumns = 7,
                        modifier = Modifier.fillMaxSize(),
                        overlayContent = { activeGridState, _ ->
                            // Fast Scrollbar overlaid on deleted items grid
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
                            items(uiState.items, key = { it.id }) { item ->
                                val isSelected = item.id in uiState.selectedItemIds
                                RecycleBinThumbnailCard(
                                    item = item,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = isSelected,
                                    isScrolling = effectiveScrolling,
                                    forceLoad = isPreviewLayer,
                                    targetSize = targetSize,
                                    aspectRatio = tileAspectRatio,
                                    onClick = {
                                        if (uiState.isSelectionMode) {
                                            viewModel.toggleSelection(item.id)
                                        } else {
                                            // In non-selection mode: open action sheet/dialog giving explicit choice
                                            itemForActionSheet = item
                                        }
                                    },
                                    onLongClick = {
                                        viewModel.toggleSelection(item.id)
                                    },
                                    onRestore = { viewModel.restoreItem(item) },
                                    onDeletePermanently = { itemToDeletePermanently = item }
                                )
                            }
                        }
                    }
                }
            }

            // Info feedback Snackbar
            uiState.infoMessage?.let { msg ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                    }
                ) { Text(msg) }
            }
        }
    }

    // Item Action Dialog / Preview Sheet (Choice between Restore & Delete Permanently)
    itemForActionSheet?.let { item ->
        AlertDialog(
            onDismissRequest = { itemForActionSheet = null },
            title = {
                Text(
                    text = item.originalName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val filePath = item.trashedFilePath.ifEmpty { item.storedFilePath }
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(File(filePath))
                            .crossfade(true)
                            .build(),
                        contentDescription = item.originalName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.height(12.dp))
                    if (item.bucketName.isNotBlank()) {
                        Text(
                            text = "Original Album: ${item.bucketName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (item.originalSizeBytes > 0) {
                        Text(
                            text = "Size: ${formatFileSize(item.originalSizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Restore button
                        FilledTonalButton(
                            onClick = {
                                val toRestore = item
                                itemForActionSheet = null
                                viewModel.restoreItem(toRestore)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.RestoreFromTrash,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Restore")
                        }

                        // Delete Permanently button
                        Button(
                            onClick = {
                                val toDelete = item
                                itemForActionSheet = null
                                itemToDeletePermanently = toDelete
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteForever,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Delete Permanently")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { itemForActionSheet = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Empty bin confirmation dialog
    if (showEmptyBinDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyBinDialog = false },
            icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Empty Recycle Bin?") },
            text = { Text("Delete permanently? All ${uiState.items.size} item(s) cannot be recovered.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.emptyRecycleBin()
                        showEmptyBinDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Empty Bin")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyBinDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete single item confirmation dialog
    itemToDeletePermanently?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDeletePermanently = null },
            icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete permanently?") },
            text = { Text("Delete permanently? This item cannot be recovered.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteItemPermanently(item)
                        itemToDeletePermanently = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDeletePermanently = null }) { Text("Cancel") }
            }
        )
    }

    // Delete selected items confirmation dialog
    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete permanently?") },
            text = { Text("Delete permanently? ${uiState.selectedItemIds.size} item(s) cannot be recovered.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedPermanently()
                        showDeleteSelectedDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups >= units.size) digitGroups = units.size - 1
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "%.1f %s".format(value, units[digitGroups])
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecycleBinThumbnailCard(
    item: RecycleBinItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit,
    isScrolling: Boolean = false,
    forceLoad: Boolean = false,
    targetSize: Int = 150,
    aspectRatio: Float = 1f,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val cacheKey = "recycle_${item.id}"
    val imageLoader = context.imageLoader
    val isInMemoryCache = imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null
    var hasLoadedInViewport by remember(cacheKey) {
        mutableStateOf(forceLoad || isInMemoryCache || !isScrolling)
    }
    val shouldLoad = forceLoad || hasLoadedInViewport || isInMemoryCache || !isScrolling
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
        modifier = modifier
            .aspectRatio(aspectRatio)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        if (shouldLoad) {
            val filePath = item.trashedFilePath.ifEmpty { item.storedFilePath }
            val decodeSize = if (forceLoad) 110 else targetSize
            val thumbRequest = remember(item.id, filePath, decodeSize) {
                ImageRequest.Builder(context)
                    .data(File(filePath))
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
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
            AsyncImage(
                model = thumbRequest,
                contentDescription = item.originalName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
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

        // Selection overlay & checkbox
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        else Color.Black.copy(alpha = 0.2f)
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(22.dp)
                            .background(Color.White, CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    )
                }
            }
        } else {
            // Quick action 3-dot menu
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
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Restore") },
                        leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, null) },
                        onClick = {
                            showMenu = false
                            onRestore()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete permanently") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDeletePermanently()
                        }
                    )
                }
            }
        }
    }
}
