package com.gallery.core.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.gallery.core.domain.model.MediaItem
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaThumbnail(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isScrolling: Boolean = false,
    targetSize: Int = 160
) {
    val cellModifier = if (isSelectionMode) {
        val animatedPadding by animateDpAsState(
            targetValue = if (isSelected) 4.dp else 0.dp,
            label = "selectedPadding"
        )
        val shape = RoundedCornerShape(if (isSelected) 8.dp else 0.dp)
        modifier
            .padding(animatedPadding)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                width = if (isSelected) 2.5.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape
            )
    } else {
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    }

    Box(
        modifier = cellModifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        val context = LocalContext.current
        val cacheKey = "thumb_${item.id}"
        val imageLoader = context.imageLoader
        val isInMemoryCache = imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null
        var shouldLoad by remember(cacheKey) {
            mutableStateOf(isInMemoryCache || !isScrolling)
        }
        if (!shouldLoad && (isInMemoryCache || !isScrolling)) {
            shouldLoad = true
        }
        DisposableEffect(cacheKey) {
            onDispose {
                shouldLoad = false
            }
        }

        if (shouldLoad) {
            val thumbnailRequest = remember(item.id, item.uri, item.filePath, targetSize) {
                ImageRequest.Builder(context)
                    .data(item.filePath?.let { File(it) }?.takeIf { it.canRead() } ?: item.uri)
                    .size(width = targetSize, height = targetSize)
                    .precision(Precision.INEXACT)
                    .scale(Scale.FILL)
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .allowHardware(true)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .crossfade(false)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            }

            AsyncImage(
                model = thumbnailRequest,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Dim / tint overlay when selected
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.32f))
            )
        }

        // Selection mode checkbox indicator (top-end)
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                            .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                    )
                }
            }
        }

        // Favorite heart badge (bottom-start)
        if (item.isFavorite) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(3.5.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = "Favorited",
                    tint = Color(0xFFFF3B30),
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        // Video duration badge
        if (item is MediaItem.Video) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.duration.formatDuration(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Play icon (hide if selected to avoid clutter)
            if (!isSelected) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50))
                        .padding(4.dp)
                )
            }
        }
    }
}

fun Long.formatDuration(): String {
    val totalSeconds = this / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
