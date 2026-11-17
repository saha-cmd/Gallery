package com.gallery.feature.vault.ui

import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.gallery.core.ui.components.SleekVideoScrubber
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
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.gallery.core.domain.model.VaultItem
import com.gallery.core.ui.components.EmptyStateView
import com.gallery.core.ui.components.SwipeToDismissBox
import com.gallery.core.ui.components.VaultInfoBottomSheet
import com.gallery.core.ui.components.VerticalFastScrollbar
import com.gallery.core.ui.util.LoadedThumbnailRegistry
import com.gallery.feature.vault.viewmodel.VaultAlbum
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.gallery.feature.vault.viewmodel.VaultTab
import com.gallery.feature.vault.viewmodel.VaultViewModel
import java.io.File
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    onEditItem: (String) -> Unit = {},
    viewModel: VaultViewModel = hiltViewModel()
) {
    val vaultState by viewModel.vaultState.collectAsStateWithLifecycle()
    val view = LocalView.current
    var itemToDelete by remember { mutableStateOf<VaultItem?>(null) }
    var showVaultMenu by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showBiometricInfoDialog by remember { mutableStateOf(false) }

    // Intercept Back button when full-screen viewer is open
    BackHandler(enabled = vaultState.activeViewingItem != null) {
        viewModel.closeViewer()
    }

    // Intercept Back button when viewing a specific album inside Vault
    BackHandler(enabled = vaultState.selectedAlbumName != null && vaultState.activeViewingItem == null) {
        viewModel.closeAlbum()
    }

    // Block screenshots and screen recording inside Vault
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        viewModel.loadVaultItems()
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val isViewingItem = vaultState.activeViewingItem != null

    Scaffold(
        topBar = {
            if (!isViewingItem) {
                val inAlbum = vaultState.selectedAlbumName != null
                val albumItems = if (inAlbum) {
                    vaultState.items.filter { it.albumName == vaultState.selectedAlbumName }
                } else {
                    vaultState.items
                }
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = if (inAlbum) vaultState.selectedAlbumName!! else "Secure Vault",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (inAlbum) "${albumItems.size} items" else "${vaultState.items.size} hidden items",
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
                        Box {
                            IconButton(onClick = { showVaultMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, "Options")
                            }
                            DropdownMenu(expanded = showVaultMenu, onDismissRequest = { showVaultMenu = false }) {
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
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!isViewingItem) Modifier.padding(paddingValues) else Modifier)
        ) {
            val inAlbum = vaultState.selectedAlbumName != null
            val displayedItems = if (inAlbum) {
                vaultState.items.filter { it.albumName == vaultState.selectedAlbumName }
            } else {
                vaultState.items
            }

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
                        val inAlbumGridState = rememberLazyGridState(
                            prefetchStrategy = remember { LazyGridPrefetchStrategy(nestedPrefetchItemCount = 0) }
                        )
                        val isInAlbumScrolling by remember { derivedStateOf { inAlbumGridState.isScrollInProgress } }
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyVerticalGrid(
                                state = inAlbumGridState,
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(displayedItems, key = { it.id }) { vaultItem ->
                                    VaultThumbnailCard(
                                        item = vaultItem,
                                        previewBitmap = vaultState.previewBitmaps[vaultItem.id],
                                        previewFile = vaultState.previewFiles[vaultItem.id],
                                        isCorrupted = vaultState.corruptedItemIds.contains(vaultItem.id),
                                        isScrolling = isInAlbumScrolling,
                                        onRequestPreview = { viewModel.requestPreview(vaultItem) },
                                        onClick = { viewModel.openViewer(vaultItem, displayedItems) },
                                        onRestore = { viewModel.restoreItemToGallery(vaultItem) },
                                        onDelete = { itemToDelete = vaultItem }
                                    )
                                }
                            }
                            VerticalFastScrollbar(
                                gridState = inAlbumGridState,
                                modifier = Modifier.fillMaxSize()
                            )
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
                                val allMediaGridState = rememberLazyGridState(
                                    prefetchStrategy = remember { LazyGridPrefetchStrategy(nestedPrefetchItemCount = 0) }
                                )
                                val isAllMediaScrolling by remember { derivedStateOf { allMediaGridState.isScrollInProgress } }
                                Box(modifier = Modifier.fillMaxSize()) {
                                    LazyVerticalGrid(
                                        state = allMediaGridState,
                                        columns = GridCells.Fixed(3),
                                        contentPadding = PaddingValues(2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(vaultState.items, key = { it.id }) { vaultItem ->
                                            VaultThumbnailCard(
                                                item = vaultItem,
                                                previewBitmap = vaultState.previewBitmaps[vaultItem.id],
                                                previewFile = vaultState.previewFiles[vaultItem.id],
                                                isCorrupted = vaultState.corruptedItemIds.contains(vaultItem.id),
                                                isScrolling = isAllMediaScrolling,
                                                onRequestPreview = { viewModel.requestPreview(vaultItem) },
                                                onClick = { viewModel.openViewer(vaultItem, vaultState.items) },
                                                onRestore = { viewModel.restoreItemToGallery(vaultItem) },
                                                onDelete = { itemToDelete = vaultItem }
                                            )
                                        }
                                    }
                                    VerticalFastScrollbar(
                                        gridState = allMediaGridState,
                                        modifier = Modifier.fillMaxSize()
                                    )
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
                                    val albumsGridState = rememberLazyGridState(
                                        prefetchStrategy = remember { LazyGridPrefetchStrategy(nestedPrefetchItemCount = 0) }
                                    )
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
                                                    coverBitmap = album.coverItem?.let { vaultState.previewBitmaps[it.id] },
                                                    coverFile = album.coverItem?.let { vaultState.previewFiles[it.id] },
                                                    isScrolling = isAlbumsScrolling,
                                                    onRequestPreview = { viewModel.requestPreview(it) },
                                                    onClick = { viewModel.openAlbum(album.name) }
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
                    onEdit = { file ->
                        onEditItem(Uri.fromFile(file).toString())
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

    if (showChangePinDialog) {
        var currentPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmNewPin by remember { mutableStateOf("") }
        var dialogError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showChangePinDialog = false },
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
}

@Composable
private fun VaultAlbumCard(
    album: VaultAlbum,
    coverBitmap: Bitmap?,
    coverFile: File?,
    onClick: () -> Unit,
    isScrolling: Boolean = false,
    onRequestPreview: (VaultItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coverItem = album.coverItem
    val cacheKey = "vault_thumb_${coverItem?.id ?: album.name}"
    val imageLoader = context.imageLoader
    val isAlreadyLoaded = coverBitmap != null ||
        LoadedThumbnailRegistry.isLoaded(cacheKey) ||
        imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null
    var shouldLoad by remember(cacheKey) {
        mutableStateOf(isAlreadyLoaded || !isScrolling)
    }
    if (isAlreadyLoaded && !shouldLoad) {
        shouldLoad = true
    }
    LaunchedEffect(isScrolling) {
        if (!isScrolling) {
            shouldLoad = true
        }
    }
    LaunchedEffect(coverItem?.id, shouldLoad) {
        if (shouldLoad && coverItem != null && coverBitmap == null) {
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
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap.asImageBitmap(),
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (shouldLoad) {
                    if (coverFile != null && coverFile.exists()) {
                        val thumbRequest = remember(coverFile.absolutePath) {
                            ImageRequest.Builder(context)
                                .data(coverFile)
                                .size(180, 180)
                                .precision(Precision.INEXACT)
                                .scale(Scale.FILL)
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .allowHardware(true)
                                .memoryCacheKey(cacheKey)
                                .diskCacheKey(cacheKey)
                                .crossfade(false)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .listener(
                                    onSuccess = { _, _ ->
                                        LoadedThumbnailRegistry.markLoaded(cacheKey)
                                    }
                                )
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

@Composable
private fun VaultThumbnailCard(
    item: VaultItem,
    previewBitmap: Bitmap?,
    previewFile: File?,
    isCorrupted: Boolean,
    onClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    isScrolling: Boolean = false,
    onRequestPreview: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val cacheKey = "vault_thumb_${item.id}"
    val imageLoader = context.imageLoader
    val isAlreadyLoaded = previewBitmap != null ||
        LoadedThumbnailRegistry.isLoaded(cacheKey) ||
        imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null
    var shouldLoad by remember(cacheKey) {
        mutableStateOf(isAlreadyLoaded || !isScrolling)
    }
    if (isAlreadyLoaded && !shouldLoad) {
        shouldLoad = true
    }
    LaunchedEffect(isScrolling) {
        if (!isScrolling) {
            shouldLoad = true
        }
    }
    LaunchedEffect(item.id, shouldLoad) {
        if (shouldLoad && previewBitmap == null) {
            onRequestPreview()
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
    ) {
        if (previewBitmap != null) {
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = item.originalName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (shouldLoad) {
            if (previewFile != null && previewFile.exists()) {
                val thumbRequest = remember(previewFile.absolutePath, item.id) {
                    ImageRequest.Builder(context)
                        .data(previewFile)
                        .size(160, 160)
                        .precision(Precision.INEXACT)
                        .scale(Scale.FILL)
                        .bitmapConfig(Bitmap.Config.RGB_565)
                        .allowHardware(true)
                        .memoryCacheKey(cacheKey)
                        .diskCacheKey(cacheKey)
                        .crossfade(false)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .listener(
                            onSuccess = { _, _ ->
                                LoadedThumbnailRegistry.markLoaded(cacheKey)
                            }
                        )
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

        // More options dropdown
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
    onEdit: (File) -> Unit,
    onDelete: (VaultItem) -> Unit
) {
    val initialIndex = remember(initialItem.id) {
        items.indexOfFirst { it.id == initialItem.id }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialIndex) { items.size }
    var isCurrentPhotoZoomed by remember { mutableStateOf(false) }
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
        isCurrentPhotoZoomed = false
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
        enabled = !isCurrentPhotoZoomed,
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
                userScrollEnabled = !isCurrentPhotoZoomed,
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
                                onVideoEnded = { areControlsVisible = true }
                            )
                        } else {
                            VaultZoomablePhoto(
                                file = pageFile,
                                item = item,
                                onTap = { areControlsVisible = !areControlsVisible },
                                onZoomChanged = {
                                    if (page == pagerState.currentPage) {
                                        isCurrentPhotoZoomed = it
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

                        // Edit Photo (photos only)
                        if (!currentItem.isVideo && currentDecryptedFile != null) {
                            IconButton(onClick = { onEdit(currentDecryptedFile) }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "Edit photo", tint = Color.White)
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

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        onZoomChanged(newScale > 1.05f)
        val maxX = (scale - 1f) * 400f
        val maxY = (scale - 1f) * 600f
        offset = Offset(
            x = (offset.x + panChange.x).coerceIn(-maxX, maxX),
            y = (offset.y + panChange.y).coerceIn(-maxY, maxY)
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                            onZoomChanged(false)
                        } else {
                            scale = 2.5f
                            onZoomChanged(true)
                        }
                    }
                )
            }
            .transformable(
                state = transformableState,
                enabled = scale > 1.05f
            )
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
                .diskCachePolicy(CachePolicy.ENABLED)
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
    onVideoEnded: () -> Unit = {}
) {
    val context = LocalContext.current
    var isMuted by remember(file.absolutePath) { mutableStateOf(false) }

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
            retriever.setDataSource(file.absolutePath)
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

    // Sync player play/pause state when page is swiped into or out of focus
    LaunchedEffect(isActivePage) {
        if (isActivePage) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
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
        modifier = Modifier.fillMaxSize()
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setOnTouchListener { _, _ -> false }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay transparent Box to capture single-tap (toggle controls) and double-tap (±10s skip)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { offset ->
                            val isRightSide = offset.x > (size.width / 2)
                            if (isRightSide) {
                                performSeek((currentPositionMs + 10_000f).toLong())
                            } else {
                                performSeek((currentPositionMs - 10_000f).toLong())
                            }
                        }
                    )
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
