package com.gallery.core.ui.components

import android.content.Context
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gallery.core.domain.model.MediaItem
import com.gallery.core.domain.model.VaultItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private data class PhotoExifData(
    val cameraModel: String? = null,
    val aperture: String? = null,
    val shutterSpeed: String? = null,
    val iso: String? = null,
    val focalLength: String? = null
)

private data class VideoMetaDetails(
    val durationFormatted: String? = null,
    val resolution: String? = null,
    val frameRate: String? = null,
    val bitrate: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInfoBottomSheet(
    item: MediaItem,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val exif = remember(item.uri) {
        if (item is MediaItem.Photo) {
            readExifData(context, item.uri)
        } else null
    }

    val videoMeta = remember(item.uri) {
        if (item is MediaItem.Video) {
            readVideoMetadata(context, item.uri)
        } else null
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.35f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = Color.White.copy(alpha = 0.65f)
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.40f),
                            Color.Black.copy(alpha = 0.82f),
                            Color.Black.copy(alpha = 0.96f)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(scrollState)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header: Title & Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                // Date & Time Banner
                InfoRowItem(
                    icon = Icons.Outlined.CalendarToday,
                    title = formatTimestamp(item.dateAdded * 1000L),
                    subtitle = "Date added"
                )

                // Main Media Specifications Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.10f)
                    ),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // File name
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )

                        HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                        // Media specs grid
                        when (item) {
                            is MediaItem.Photo -> {
                                val mp = if (item.width > 0 && item.height > 0) {
                                    String.format(Locale.getDefault(), "%.1f MP", (item.width.toLong() * item.height) / 1_000_000.0)
                                } else null

                                val dimenText = if (item.width > 0 && item.height > 0) {
                                    "${item.width} × ${item.height}" + (if (mp != null) " • $mp" else "")
                                } else "Unknown dimensions"

                                DetailPair(label = "Resolution", value = dimenText)
                                DetailPair(label = "File size", value = formatFileSize(item.size))
                                DetailPair(label = "Format", value = item.mimeType)
                            }
                            is MediaItem.Video -> {
                                val durationText = videoMeta?.durationFormatted
                                    ?: if (item.duration > 0) formatDurationString(item.duration) else null
                                val resText = videoMeta?.resolution
                                    ?: item.resolution.ifBlank { "Standard Video" }

                                durationText?.let { DetailPair(label = "Duration", value = it) }
                                DetailPair(label = "Resolution", value = resText)
                                DetailPair(label = "File size", value = formatFileSize(item.size))
                                videoMeta?.frameRate?.let { DetailPair(label = "Frame rate", value = it) }
                                videoMeta?.bitrate?.let { DetailPair(label = "Bitrate", value = it) }
                            }
                        }
                    }
                }

                // Camera / EXIF Details Card (for photos when camera metadata is available)
                if (exif != null && (exif.cameraModel != null || exif.aperture != null || exif.iso != null)) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.10f)
                        ),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.PhotoCamera,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = exif.cameraModel ?: "Camera Details",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }

                            val specs = listOfNotNull(
                                exif.aperture,
                                exif.shutterSpeed,
                                exif.focalLength,
                                exif.iso
                            ).joinToString("  •  ")

                            if (specs.isNotBlank()) {
                                Text(
                                    text = specs,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }

                // Storage Location Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.10f)
                    ),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = item.bucketName.ifBlank { "Storage" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }

                        val displayPath = item.filePath?.ifBlank { null }
                            ?: "Internal Storage • ${item.bucketName} / ${item.name}"
                        Text(
                            text = displayPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultInfoBottomSheet(
    item: VaultItem,
    decryptedFile: File?,
    onDismissRequest: () -> Unit
) {
    val scrollState = rememberScrollState()

    val fileSpecs = remember(decryptedFile) {
        if (decryptedFile != null && decryptedFile.exists()) {
            if (item.isVideo) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(decryptedFile.absolutePath)
                    val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    retriever.release()
                    val res = if (w != null && h != null) "$w × $h" else null
                    val dur = if (d > 0) formatDurationString(d) else null
                    Pair(dur, res)
                } catch (e: Exception) {
                    Pair(null, null)
                }
            } else {
                try {
                    val exif = ExifInterface(decryptedFile.absolutePath)
                    val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    val res = if (w > 0 && h > 0) "$w × $h" else null
                    Pair(null, res)
                } catch (e: Exception) {
                    Pair(null, null)
                }
            }
        } else Pair(null, null)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.35f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = Color.White.copy(alpha = 0.65f)
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.40f),
                            Color.Black.copy(alpha = 0.82f),
                            Color.Black.copy(alpha = 0.96f)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(scrollState)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Secure Item Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                // Security Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(0.5.dp, Color(0xFF81C784).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = Color(0xFF81C784),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Secure Vault Encrypted",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = "AES-256 GCM authenticated encryption",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }
                }

                // Date Added
                InfoRowItem(
                    icon = Icons.Outlined.CalendarToday,
                    title = formatTimestamp(item.addedAt),
                    subtitle = "Moved to vault"
                )

                // Main Specs Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.10f)
                    ),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = item.originalName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )

                        HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                        fileSpecs.first?.let { DetailPair(label = "Duration", value = it) }
                        fileSpecs.second?.let { DetailPair(label = "Resolution", value = it) }
                        DetailPair(label = "File size", value = formatFileSize(item.originalSizeBytes))
                        DetailPair(label = "MIME type", value = item.mimeType)
                        DetailPair(label = "Vault Album", value = item.albumName)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRowItem(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.70f)
            )
        }
    }
}

@Composable
private fun DetailPair(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.70f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "Unknown"
    val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy · h:mm a", Locale.getDefault())
    return sdf.format(Date(millis))
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
        else -> "$bytes B"
    }
}

private fun formatDurationString(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

private fun readExifData(context: Context, uri: Uri): PhotoExifData? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val make = exif.getAttribute(ExifInterface.TAG_MAKE)
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)
            val camera = when {
                make != null && model != null && !model.contains(make, ignoreCase = true) -> "$make $model"
                model != null -> model
                else -> make
            }
            val aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" }
            val exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let {
                val sec = it.toDoubleOrNull()
                if (sec != null && sec > 0.0) {
                    if (sec < 1.0) "1/${(1.0 / sec).toInt()}s" else "${sec}s"
                } else it
            }
            val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                ?: exif.getAttribute("PhotographicSensitivity")
            val focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let {
                val mm = it.toDoubleOrNull()
                if (mm != null) String.format(Locale.getDefault(), "%.1f mm", mm) else "$it mm"
            }

            PhotoExifData(
                cameraModel = camera,
                aperture = aperture,
                shutterSpeed = exposure,
                iso = iso?.let { "ISO $it" },
                focalLength = focal
            )
        }
    } catch (e: Exception) {
        null
    }
}

private fun readVideoMetadata(context: Context, uri: Uri): VideoMetaDetails? {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        val frameRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
        } else null
        retriever.release()

        val actualW = if (rotation == 90 || rotation == 270) height else width
        val actualH = if (rotation == 90 || rotation == 270) width else height
        val res = if (actualW != null && actualH != null) "$actualW × $actualH" else null
        val bitrateStr = if (bitrate != null && bitrate > 0) {
            String.format(Locale.getDefault(), "%.1f Mbps", bitrate / 1_000_000.0)
        } else null

        VideoMetaDetails(
            durationFormatted = if (durationMs > 0) formatDurationString(durationMs) else null,
            resolution = res,
            frameRate = frameRate?.let { "$it fps" },
            bitrate = bitrateStr
        )
    } catch (e: Exception) {
        null
    }
}
