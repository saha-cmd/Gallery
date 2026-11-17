package com.gallery.core.ui.components

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gallery.core.domain.model.AppUpdateInfo
import com.gallery.core.domain.model.AppUpdateState
import com.gallery.core.ui.updater.ApkInstallerHelper
import java.util.Locale

@Composable
fun AppUpdateDialog(
    updateState: AppUpdateState,
    onDismiss: () -> Unit,
    onStartDownload: (AppUpdateInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onSkipVersion: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    currentVersionName: String? = null
) {
    // Only display dialog for actionable states
    if (updateState is AppUpdateState.Idle ||
        updateState is AppUpdateState.Checking ||
        updateState is AppUpdateState.UpToDate
    ) {
        return
    }

    val context = LocalContext.current
    var waitingForInstallPermission by remember { mutableStateOf(false) }

    // Derive installed version name for the update badge
    val resolvedCurrentVersion = remember(context, currentVersionName) {
        currentVersionName ?: runCatching {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
    }

    // Auto-install when returning from system unknown sources settings screen
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (waitingForInstallPermission && updateState is AppUpdateState.ReadyToInstall) {
            if (ApkInstallerHelper.canRequestPackageInstalls(context)) {
                waitingForInstallPermission = false
                ApkInstallerHelper.installApk(context, updateState.apkFile)
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (updateState !is AppUpdateState.Downloading) {
                onDismiss()
            }
        },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        icon = {
            val icon: ImageVector = when (updateState) {
                is AppUpdateState.UpdateAvailable -> Icons.Outlined.RocketLaunch
                is AppUpdateState.Downloading -> Icons.Outlined.CloudDownload
                is AppUpdateState.ReadyToInstall -> {
                    if (waitingForInstallPermission && !ApkInstallerHelper.canRequestPackageInstalls(context)) {
                        Icons.Outlined.Security
                    } else {
                        Icons.Outlined.CheckCircle
                    }
                }
                is AppUpdateState.Error -> Icons.Outlined.ErrorOutline
                else -> Icons.Outlined.RocketLaunch
            }

            val iconTint = when (updateState) {
                is AppUpdateState.Error -> MaterialTheme.colorScheme.error
                is AppUpdateState.ReadyToInstall -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.primary
            }

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = when (updateState) {
                    is AppUpdateState.UpdateAvailable -> "Update Available"
                    is AppUpdateState.Downloading -> "Downloading Update"
                    is AppUpdateState.ReadyToInstall -> {
                        if (waitingForInstallPermission && !ApkInstallerHelper.canRequestPackageInstalls(context)) {
                            "Permission Required"
                        } else {
                            "Download Complete"
                        }
                    }
                    is AppUpdateState.Error -> "Update Failed"
                    else -> ""
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            when (updateState) {
                is AppUpdateState.UpdateAvailable -> {
                    UpdateAvailableContent(
                        info = updateState.info,
                        currentVersion = resolvedCurrentVersion
                    )
                }
                is AppUpdateState.Downloading -> {
                    DownloadingContent(state = updateState)
                }
                is AppUpdateState.ReadyToInstall -> {
                    ReadyToInstallContent(
                        info = updateState.info,
                        isWaitingForPermission = waitingForInstallPermission &&
                                !ApkInstallerHelper.canRequestPackageInstalls(context)
                    )
                }
                is AppUpdateState.Error -> {
                    ErrorContent(message = updateState.message)
                }
                else -> {}
            }
        },
        confirmButton = {
            when (updateState) {
                is AppUpdateState.UpdateAvailable -> {
                    Button(
                        onClick = { onStartDownload(updateState.info) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Update Now")
                    }
                }
                is AppUpdateState.Downloading -> {
                    // Downloading state uses dismiss button for Cancel
                }
                is AppUpdateState.ReadyToInstall -> {
                    Button(
                        onClick = {
                            if (ApkInstallerHelper.canRequestPackageInstalls(context)) {
                                ApkInstallerHelper.installApk(context, updateState.apkFile)
                            } else {
                                waitingForInstallPermission = true
                                ApkInstallerHelper.openUnknownAppSourcesSettings(context)
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        val text = if (waitingForInstallPermission && !ApkInstallerHelper.canRequestPackageInstalls(context)) {
                            "Open Settings"
                        } else {
                            "Install Now"
                        }
                        Text(text)
                    }
                }
                is AppUpdateState.Error -> {
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Retry")
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            when (updateState) {
                is AppUpdateState.UpdateAvailable -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onSkipVersion(updateState.info.versionCode) }
                        ) {
                            Text(
                                "Skip Version",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Later")
                        }
                    }
                }
                is AppUpdateState.Downloading -> {
                    OutlinedButton(
                        onClick = onCancelDownload,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                }
                is AppUpdateState.ReadyToInstall -> {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Later")
                    }
                }
                is AppUpdateState.Error -> {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Close")
                    }
                }
                else -> {}
            }
        }
    )
}

@Composable
private fun UpdateAvailableContent(
    info: AppUpdateInfo,
    currentVersion: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Version Transition Badge & Download Size Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "v$currentVersion → v${info.versionName}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (info.fileSizeBytes > 0) {
                val sizeMb = info.fileSizeBytes / (1024.0 * 1024.0)
                Text(
                    text = String.format(Locale.US, "%.1f MB", sizeMb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Release Notes Scrollable Card
        if (info.releaseNotes.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Release Notes",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = info.releaseNotes.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadingContent(
    state: AppUpdateState.Downloading
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.totalBytes > 0) {
                    "${(state.progress * 100).toInt()}%"
                } else {
                    "Downloading..."
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            if (state.totalBytes > 0) {
                val downloadedMb = state.downloadedBytes / (1024.0 * 1024.0)
                val totalMb = state.totalBytes / (1024.0 * 1024.0)
                Text(
                    text = String.format(Locale.US, "%.1f / %.1f MB", downloadedMb, totalMb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
private fun ReadyToInstallContent(
    info: AppUpdateInfo,
    isWaitingForPermission: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isWaitingForPermission) {
            Text(
                text = "To install the update, please enable \"Install unknown apps\" for Gallery in system settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "Gallery v${info.versionName} has finished downloading and is ready to install.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String
) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
}
