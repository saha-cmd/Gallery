package com.gallery.core.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
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
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.gallery.core.domain.model.Album
import com.gallery.core.domain.model.MediaItem

@Composable
fun ChangeAlbumCoverDialog(
    album: Album,
    onDismissRequest: () -> Unit,
    onSelectCover: (Uri) -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Change Album Cover", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Select an image or video to use as the cover for this album:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                if (album.items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No items in this album",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val gridState = rememberViewportOnlyLazyGridState()
                    val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress } }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(album.items, key = { it.id }) { item ->
                            val isCurrentCover = (item.uri == album.coverUri || item.uri.toString() == album.coverUri.toString())
                            val cacheKey = "thumb_${item.id}"
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
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .then(
                                        if (isCurrentCover) {
                                            Modifier.border(
                                                BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                                                MaterialTheme.shapes.small
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable { onSelectCover(item.uri) }
                            ) {
                                if (shouldLoad) {
                                    val thumbRequest = remember(item.id, item.uri) {
                                        ImageRequest.Builder(context)
                                            .data(item.uri)
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
                                            .build()
                                    }
                                    AsyncImage(
                                        model = thumbRequest,
                                        contentDescription = item.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                if (item is MediaItem.Video) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(4.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                            .padding(2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = "Video",
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }

                                if (isCurrentCover) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                    )
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(20.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = "Current Cover",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}
