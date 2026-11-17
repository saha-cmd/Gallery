package com.gallery.core.domain.repository

import com.gallery.core.domain.model.GallerySettings
import com.gallery.core.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<GallerySettings>
    val isXdrDisplayEnabled: Flow<Boolean>
    val isAutoHideControlsEnabled: Flow<Boolean>
    val defaultGridColumns: Flow<Int>
    val themeMode: Flow<ThemeMode>
    val lastUpdateCheckMillis: Flow<Long>
    val skippedVersionCode: Flow<Long>
    val skippedVersionName: Flow<String>
    val remindLaterTimestampMillis: Flow<Long>

    suspend fun setXdrDisplayEnabled(enabled: Boolean)
    suspend fun setAutoHideControlsEnabled(enabled: Boolean)
    suspend fun setDefaultGridColumns(columns: Int)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setMergeAlbums(enabled: Boolean)
    suspend fun setSelectEssentialAlbums(enabled: Boolean)
    suspend fun setEssentialAlbumNames(names: Set<String>)
    suspend fun toggleEssentialAlbum(albumName: String)
    suspend fun setLastUpdateCheckMillis(timeMillis: Long)
    suspend fun setSkippedVersionCode(versionCode: Long)
    suspend fun setSkippedVersion(versionName: String)
    suspend fun setRemindLaterTimestamp(timestampMillis: Long)
    suspend fun clearUpdateSuppression()
}
