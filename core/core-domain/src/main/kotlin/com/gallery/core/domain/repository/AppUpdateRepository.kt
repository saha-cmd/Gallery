package com.gallery.core.domain.repository

import com.gallery.core.domain.model.AppUpdateInfo
import com.gallery.core.domain.model.AppUpdateState
import kotlinx.coroutines.flow.StateFlow

interface AppUpdateRepository {
    val updateState: StateFlow<AppUpdateState>
    suspend fun checkForUpdates(isManual: Boolean = false)
    suspend fun downloadUpdate(info: AppUpdateInfo)
    fun cancelDownload()
    suspend fun skipVersion(versionCode: Long)
    fun dismissUpdateState()
}
