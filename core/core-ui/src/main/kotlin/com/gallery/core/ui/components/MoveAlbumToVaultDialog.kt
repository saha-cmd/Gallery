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
import com.gallery.core.domain.model.Album

@Composable
fun MoveAlbumToVaultDialog(
    selectedAlbums: List<Album>,
    existingVaultAlbums: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (keepOriginalNames: Boolean, targetVaultAlbum: String) -> Unit
) {
    val totalItems = remember(selectedAlbums) { selectedAlbums.sumOf { it.itemCount } }
    val isMultiAlbum = selectedAlbums.size > 1

    // Multiple albums options:
    // Option A: Keep original album names (preserve folder structure)
    // Option B: Combine into one Secure Folder album
    var keepOriginalAlbumNames by remember { mutableStateOf(isMultiAlbum) }

    val allVaultAlbums = remember(existingVaultAlbums, selectedAlbums) {
        val list = existingVaultAlbums.toMutableList()
        if ("Default" !in list) list.add(0, "Default")
        // If single album, ensure its name is in the suggestion list
        if (!isMultiAlbum && selectedAlbums.isNotEmpty()) {
            val singleName = selectedAlbums.first().name
            if (singleName !in list) list.add(0, singleName)
        }
        list.distinct()
    }

    var selectedVaultAlbum by remember(selectedAlbums, isMultiAlbum) {
        mutableStateOf(
            if (!isMultiAlbum && selectedAlbums.isNotEmpty()) {
                selectedAlbums.first().name
            } else {
                allVaultAlbums.firstOrNull() ?: "Default"
            }
        )
    }

    var isCreatingNewAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        icon = {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = if (isMultiAlbum) {
                    "Move ${selectedAlbums.size} Albums to Secure Vault"
                } else {
                    "Move \"${selectedAlbums.firstOrNull()?.name ?: "Album"}\" to Secure Vault"
                },
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
                    text = "Items inside ($totalItems photo/video${if (totalItems != 1) "s" else ""}) will be encrypted and hidden from the gallery.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isMultiAlbum) {
                    Text(
                        text = "Organization in Secure Vault:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    // Radio 1: Keep original album names
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { keepOriginalAlbumNames = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = keepOriginalAlbumNames,
                            onClick = { keepOriginalAlbumNames = true }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Keep original album names",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (keepOriginalAlbumNames) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                text = "Preserves each album's folder structure in the vault",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Radio 2: Combine into one Secure Folder album
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { keepOriginalAlbumNames = false }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !keepOriginalAlbumNames,
                            onClick = { keepOriginalAlbumNames = false }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Combine into one Secure Folder album",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (!keepOriginalAlbumNames) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                text = "Puts all items into a single chosen vault album",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider()
                }

                // If single album OR "Combine into one Secure Folder album" is selected:
                if (!isMultiAlbum || !keepOriginalAlbumNames) {
                    Text(
                        text = if (isMultiAlbum) "Choose destination vault album:" else "Destination vault album:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (!isCreatingNewAlbum) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                        ) {
                            items(allVaultAlbums) { albumName ->
                                val isSelected = (albumName == selectedVaultAlbum)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedVaultAlbum = albumName }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedVaultAlbum = albumName }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = albumName,
                                        style = MaterialTheme.typography.bodyMedium,
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
                            Text("Create New Vault Album")
                        }
                    } else {
                        OutlinedTextField(
                            value = newAlbumName,
                            onValueChange = {
                                newAlbumName = it
                                errorText = null
                            },
                            label = { Text("New Vault Album Name") },
                            placeholder = { Text("e.g. Private, Documents, Secret") },
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isMultiAlbum && keepOriginalAlbumNames) {
                        onConfirm(true, "")
                    } else {
                        val target = if (isCreatingNewAlbum) {
                            val trimmed = newAlbumName.trim()
                            if (trimmed.isEmpty()) {
                                errorText = "Album name cannot be empty"
                                return@Button
                            }
                            trimmed
                        } else {
                            selectedVaultAlbum
                        }
                        onConfirm(false, target)
                    }
                }
            ) {
                Text("Move to Vault")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
