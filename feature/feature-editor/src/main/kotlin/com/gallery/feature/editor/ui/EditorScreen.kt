package com.gallery.feature.editor.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter as ComposeColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gallery.feature.editor.viewmodel.ColorFilter
import com.gallery.feature.editor.viewmodel.EditorViewModel
import com.yalantis.ucrop.UCrop
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    mediaUriString: String,
    isVideo: Boolean = false,
    isVault: Boolean = false,
    vaultItemId: String = "",
    onBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val resolvedIsVideo = remember(mediaUriString, isVideo) {
        isVideo || isVideoUri(context, Uri.parse(mediaUriString))
    }

    if (resolvedIsVideo) {
        VideoEditorScreen(
            mediaUriString = mediaUriString,
            onBack = onBack
        )
        return
    }

    val isFromVault = remember(mediaUriString, isVault) {
        isVault || mediaUriString.contains("vault_secure_temp") || mediaUriString.contains(".vault")
    }

    DisposableEffect(isFromVault) {
        val window = (context as? android.app.Activity)?.window
        if (isFromVault) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (isFromVault) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    fun handleBack() {
        if (isFromVault) {
            try {
                File(context.cacheDir, "vault_secure_temp").listFiles { _, name -> name.startsWith("crop_") }?.forEach { it.delete() }
                context.cacheDir.listFiles { _, name -> name.startsWith("crop_") }?.forEach { it.delete() }
            } catch (_: Exception) {}
        }
        onBack()
    }

    BackHandler {
        handleBack()
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAdjustPanel by remember { mutableStateOf(false) }

    LaunchedEffect(mediaUriString, isFromVault, vaultItemId) {
        val uri = Uri.parse(mediaUriString)
        viewModel.setSourceUri(uri)
        if (isFromVault) {
            viewModel.setIsVault(true, vaultItemId)
        }
    }

    // UCrop Activity Result Launcher
    val cropLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) {
                viewModel.setSourceUri(resultUri)
            }
        }
    }

    // Color matrix calculation for Compose preview
    val composeColorFilter = remember(uiState.saturation, uiState.brightness) {
        val cm = ColorMatrix()
        cm.setToSaturation(uiState.saturation)
        val b = (uiState.brightness - 1.0f) * 255f
        // Apply brightness offset
        val brightnessMatrix = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f
            )
        )
        brightnessMatrix.timesAssign(cm)
        ComposeColorFilter.colorMatrix(brightnessMatrix)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo Editor", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = ::handleBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            uiState.sourceUri?.let { sourceUri ->
                                val destFolder = if (isFromVault) {
                                    File(context.cacheDir, "vault_secure_temp").apply { mkdirs() }
                                } else {
                                    context.cacheDir
                                }
                                val destinationUri = Uri.fromFile(
                                    File(destFolder, "crop_${System.currentTimeMillis()}.jpg")
                                )
                                val uCrop = UCrop.of(sourceUri, destinationUri)
                                    .withOptions(UCrop.Options().apply {
                                        setHideBottomControls(false)
                                        setFreeStyleCropEnabled(true)
                                    })
                                cropLauncher.launch(uCrop.getIntent(context))
                            }
                        }
                    ) {
                        Icon(Icons.Outlined.Crop, contentDescription = "Crop")
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
            // Main image preview
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                uiState.sourceUri?.let { uri ->
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Edit preview",
                        contentScale = ContentScale.Fit,
                        colorFilter = composeColorFilter,
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(uiState.rotationDegrees.toFloat())
                    )
                }

                if (uiState.isSaving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            Text("Saving copy...", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // Adjust panel (Sliders for Brightness and Saturation)
            if (showAdjustPanel) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Brightness", style = MaterialTheme.typography.labelMedium)
                            Text("${(uiState.brightness * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                        Slider(
                            value = uiState.brightness,
                            onValueChange = viewModel::adjustBrightness,
                            valueRange = 0.5f..1.5f
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Saturation", style = MaterialTheme.typography.labelMedium)
                            Text("${(uiState.saturation * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                        Slider(
                            value = uiState.saturation,
                            onValueChange = viewModel::adjustSaturation,
                            valueRange = 0f..2.0f
                        )
                    }
                }
            }

            // Preset Filter chips
            Text(
                text = "Preset Filters",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                items(ColorFilter.entries) { filter ->
                    FilterChipItem(
                        label = filter.displayName,
                        isSelected = uiState.activeFilter == filter,
                        onClick = { viewModel.applyFilter(filter) }
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

            // Tool action row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EditorActionButton(
                    icon = { Icon(Icons.Outlined.RotateLeft, "Rotate Left", tint = Color.White) },
                    label = "Rotate -90°",
                    onClick = { viewModel.rotate(-90) }
                )
                EditorActionButton(
                    icon = { Icon(Icons.Outlined.RotateRight, "Rotate Right", tint = Color.White) },
                    label = "Rotate +90°",
                    onClick = { viewModel.rotate(90) }
                )
                EditorActionButton(
                    icon = {
                        Icon(
                            imageVector = if (showAdjustPanel) Icons.Outlined.Check else Icons.Outlined.Tune,
                            contentDescription = "Adjust",
                            tint = Color.White
                        )
                    },
                    label = if (showAdjustPanel) "Done" else "Tune",
                    onClick = { showAdjustPanel = !showAdjustPanel }
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

            // Save / Discard buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = ::handleBack,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f))
                ) {
                    Text("Discard")
                }
                Button(
                    onClick = {
                        viewModel.saveEdit { _ ->
                            handleBack()
                        }
                    },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save Copy")
                }
            }
        }
    }
}

private fun isVideoUri(context: Context, uri: Uri): Boolean {
    val resolvedMime = try {
        context.contentResolver.getType(uri)
    } catch (_: Exception) {
        null
    }
    if (resolvedMime?.startsWith("video/", ignoreCase = true) == true) {
        return true
    }
    val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
    if (ext.isNotEmpty()) {
        val extMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (extMime?.startsWith("video/", ignoreCase = true) == true) {
            return true
        }
    }
    return ext in setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v")
}

@Composable
internal fun FilterChipItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f))
            .border(
                width = if (isSelected) 0.dp else 1.dp,
                color = if (isSelected) Color.Transparent else Color.White.copy(alpha = 0.25f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun EditorActionButton(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        icon()
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
    }
}
