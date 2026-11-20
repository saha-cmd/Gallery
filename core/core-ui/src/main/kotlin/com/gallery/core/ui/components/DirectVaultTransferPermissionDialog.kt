package com.gallery.core.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DirectVaultTransferPermissionDialog(
    onDismissRequest: () -> Unit,
    onAllowDirectTransfer: () -> Unit,
    onContinueWithSystemPopup: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        icon = {
            Icon(
                Icons.Outlined.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Enable Direct Secure Folder Transfer",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Allow All Files Access once so Gallery can move photos and videos directly into the Secure Folder without Android's delete confirmation popup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAllowDirectTransfer
            ) {
                Text("Allow Direct Transfer")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismissRequest) {
                    Text("Cancel")
                }
                TextButton(onClick = onContinueWithSystemPopup) {
                    Text("Continue with System Popup")
                }
            }
        }
    )
}
