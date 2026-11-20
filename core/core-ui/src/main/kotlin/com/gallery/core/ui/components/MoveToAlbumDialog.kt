package com.gallery.core.ui.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.gallery.core.domain.model.Album

@Composable
fun MoveToAlbumDialog(
    itemCount: Int,
    albums: List<Album>,
    currentAlbumId: Long? = null,
    onDismiss: () -> Unit,
    onConfirm: (targetAlbum: Album?, newAlbumName: String?) -> Unit
) {
    val eligibleAlbums = remember(albums, currentAlbumId) {
        albums.filter { album ->
            album.id != currentAlbumId &&
                !album.isSmartAlbum &&
                album.id != Album.ID_ALL_MEDIA &&
                album.id != Album.ID_VIDEOS &&
                album.id != Album.ID_FAVORITES
        }
    }

    var selectedAlbum by remember(eligibleAlbums) { mutableStateOf(eligibleAlbums.firstOrNull()) }
    var isCreatingNewAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        icon = {
            Icon(
                imageVector = Icons.Outlined.DriveFileMove,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = if (itemCount > 1) "Move $itemCount items to Album" else "Move to Album",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!isCreatingNewAlbum) {
                    Text(
                        text = "Select a destination album or create a new one:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (eligibleAlbums.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                        ) {
                            items(eligibleAlbums, key = { it.id }) { album ->
                                val isSelected = (album.id == selectedAlbum?.id)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedAlbum = album }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedAlbum = album }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        if (album.coverUri != Uri.EMPTY) {
                                            AsyncImage(
                                                model = album.coverUri,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(6.dp))
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Outlined.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(10.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = album.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${album.itemCount} items" + if (album.isCustom) " · Custom Album" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "No other albums available. Tap '+ Create New Album' to create one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            isCreatingNewAlbum = true
                            newAlbumName = ""
                            errorText = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Create New Album")
                    }
                } else {
                    Text(
                        text = "Enter a name for the new album:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = newAlbumName,
                        onValueChange = {
                            newAlbumName = it
                            errorText = null
                        },
                        label = { Text("New Album Name") },
                        placeholder = { Text("e.g. Travel, Personal, Documents") },
                        singleLine = true,
                        isError = errorText != null,
                        supportingText = errorText?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )

                    TextButton(
                        onClick = { isCreatingNewAlbum = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Back to Album List")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isCreatingNewAlbum) {
                        val trimmed = newAlbumName.trim()
                        if (trimmed.isEmpty()) {
                            errorText = "Album name cannot be empty"
                            return@Button
                        }
                        onConfirm(null, trimmed)
                    } else {
                        val target = selectedAlbum
                        if (target == null) {
                            errorText = "Please select an album"
                            return@Button
                        }
                        onConfirm(target, null)
                    }
                },
                enabled = if (isCreatingNewAlbum) newAlbumName.trim().isNotEmpty() else (selectedAlbum != null)
            ) {
                Text(if (isCreatingNewAlbum) "Create & Move" else "Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
