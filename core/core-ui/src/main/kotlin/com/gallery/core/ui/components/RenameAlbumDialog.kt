package com.gallery.core.ui.components

import androidx.compose.runtime.Composable

@Composable
fun RenameAlbumDialog(
    initialName: String,
    onDismissRequest: () -> Unit,
    onConfirm: (newName: String) -> Unit
) {
    CreateAlbumDialog(
        title = "Rename Album",
        initialName = initialName,
        confirmButtonText = "Rename",
        onDismissRequest = onDismissRequest,
        onConfirm = onConfirm
    )
}
