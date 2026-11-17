package com.gallery.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun MoveToVaultAlbumDialog(
    itemCount: Int,
    existingAlbums: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (albumName: String) -> Unit
) {
    val albums = remember(existingAlbums) {
        val list = if (existingAlbums.isEmpty()) listOf("Default") else existingAlbums
        if ("Default" !in list) listOf("Default") + list else list
    }
    var selectedAlbum by remember { mutableStateOf(albums.firstOrNull() ?: "Default") }
    var isCreatingNewAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = {
            Text(
                text = if (itemCount > 1) "Move $itemCount items to Secure Folder" else "Move to Secure Folder",
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
                Text(
                    text = "Select an existing album or create a new album inside your Secure Folder:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!isCreatingNewAlbum) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                    ) {
                        items(albums) { album ->
                            val isSelected = (album == selectedAlbum)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedAlbum = album }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedAlbum = album }
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = album,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
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
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Create New Album")
                    }
                } else {
                    OutlinedTextField(
                        value = newAlbumName,
                        onValueChange = {
                            newAlbumName = it
                            errorText = null
                        },
                        label = { Text("New Album Name") },
                        placeholder = { Text("e.g. Personal, Work, Documents") },
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
                    val targetAlbum = if (isCreatingNewAlbum) {
                        val trimmed = newAlbumName.trim()
                        if (trimmed.isEmpty()) {
                            errorText = "Album name cannot be empty"
                            return@Button
                        }
                        trimmed
                    } else {
                        selectedAlbum
                    }
                    onConfirm(targetAlbum)
                }
            ) {
                Text("Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
