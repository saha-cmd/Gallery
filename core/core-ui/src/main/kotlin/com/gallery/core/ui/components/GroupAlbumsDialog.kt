package com.gallery.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gallery.core.domain.model.Album
import com.gallery.core.domain.model.UserAlbumGroup

@Composable
fun GroupAlbumsDialog(
    allAlbums: List<Album>,
    initialSelectedAlbumKeys: Set<String> = emptySet(),
    existingGroups: List<UserAlbumGroup> = emptyList(),
    albumGroupMap: Map<String, String> = emptyMap(),
    onDismissRequest: () -> Unit,
    onConfirm: (groupName: String, selectedAlbumKeys: List<String>, existingGroupId: Long?) -> Unit
) {
    var selectedKeys by remember(initialSelectedAlbumKeys) { mutableStateOf(initialSelectedAlbumKeys) }
    var isNewGroupMode by remember { mutableStateOf(true) }
    var newGroupName by remember { mutableStateOf("") }
    var selectedExistingGroup by remember { mutableStateOf(existingGroups.firstOrNull()) }

    val isValid = remember(selectedKeys, isNewGroupMode, newGroupName, selectedExistingGroup) {
        val count = selectedKeys.size
        if (isNewGroupMode) {
            count >= 2 && newGroupName.trim().isNotBlank()
        } else {
            count >= 1 && selectedExistingGroup != null
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Outlined.FolderCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text("Group Albums")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Group mode toggle (New Group vs Existing Group)
                if (existingGroups.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = isNewGroupMode,
                            onClick = { isNewGroupMode = true },
                            label = { Text("New Group") }
                        )
                        FilterChip(
                            selected = !isNewGroupMode,
                            onClick = { isNewGroupMode = false },
                            label = { Text("Existing Group") }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (isNewGroupMode) {
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text("Group Name (e.g. Trips, Work)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = "Choose existing group:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(existingGroups) { group ->
                            val isSelected = selectedExistingGroup?.id == group.id
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedExistingGroup = group },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isNewGroupMode) "Select 2 or more albums (${selectedKeys.size} selected):"
                               else "Select albums to add (${selectedKeys.size} selected):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(allAlbums, key = { it.id }) { album ->
                        val albumKey = "album_${album.id}"
                        val isChecked = albumKey in selectedKeys

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedKeys = if (isChecked) {
                                        selectedKeys - albumKey
                                    } else {
                                        selectedKeys + albumKey
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedKeys = if (checked) {
                                        selectedKeys + albumKey
                                    } else {
                                        selectedKeys - albumKey
                                    }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            if (album.coverUri.toString().isNotEmpty()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(album.coverUri)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.PhotoAlbum,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = album.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                val currentGroupName = albumGroupMap[albumKey] ?: albumGroupMap["id_${album.id}"] ?: albumGroupMap[album.id.toString()]
                                val subtitleText = if (!currentGroupName.isNullOrBlank()) {
                                    "${album.itemCount} items • $currentGroupName"
                                } else {
                                    "${album.itemCount} items"
                                }
                                Text(
                                    text = subtitleText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isNewGroupMode) {
                        onConfirm(newGroupName.trim(), selectedKeys.toList(), null)
                    } else {
                        selectedExistingGroup?.let { group ->
                            onConfirm(group.name, selectedKeys.toList(), group.id)
                        }
                    }
                },
                enabled = isValid
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}
