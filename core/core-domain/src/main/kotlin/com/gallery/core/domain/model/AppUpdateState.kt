package com.gallery.core.domain.model

import java.io.File

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class UpdateAvailable(val info: AppUpdateInfo) : AppUpdateState
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : AppUpdateState
    data class ReadyToInstall(val apkFile: File, val info: AppUpdateInfo) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}
