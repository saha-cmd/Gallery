package com.gallery.core.data.repository

import android.content.Context
import android.provider.MediaStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gallery.core.domain.model.GallerySettings
import com.gallery.core.domain.model.ThemeMode
import com.gallery.core.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gallery_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private object PreferencesKeys {
        val XDR_DISPLAY_ENABLED = booleanPreferencesKey("xdr_display_enabled")
        val AUTO_HIDE_CONTROLS = booleanPreferencesKey("auto_hide_controls")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val MERGE_ALBUMS = booleanPreferencesKey("merge_albums")
        val SELECT_ESSENTIAL_ALBUMS = booleanPreferencesKey("select_essential_albums")
        val ESSENTIAL_ALBUM_NAMES = stringSetPreferencesKey("essential_album_names")
        val LAST_UPDATE_CHECK_MILLIS = longPreferencesKey("last_update_check_millis")
        val SKIPPED_VERSION_CODE = longPreferencesKey("skipped_version_code")
        val SKIPPED_VERSION_NAME = stringPreferencesKey("skipped_version_name")
        val REMIND_LATER_TIMESTAMP_MILLIS = longPreferencesKey("remind_later_timestamp_millis")
    }

    override val settings: Flow<GallerySettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            GallerySettings(
                selectEssentialAlbums = preferences[PreferencesKeys.SELECT_ESSENTIAL_ALBUMS] ?: false,
                essentialAlbumNames = preferences[PreferencesKeys.ESSENTIAL_ALBUM_NAMES] ?: emptySet(),
                mergeAlbums = preferences[PreferencesKeys.MERGE_ALBUMS] ?: false,
                lastUpdateCheckMillis = preferences[PreferencesKeys.LAST_UPDATE_CHECK_MILLIS] ?: 0L,
                skippedVersionCode = preferences[PreferencesKeys.SKIPPED_VERSION_CODE] ?: 0L,
                skippedVersionName = preferences[PreferencesKeys.SKIPPED_VERSION_NAME] ?: "",
                remindLaterTimestampMillis = preferences[PreferencesKeys.REMIND_LATER_TIMESTAMP_MILLIS] ?: 0L
            )
        }

    override val isXdrDisplayEnabled: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.XDR_DISPLAY_ENABLED] ?: true
        }

    override val isAutoHideControlsEnabled: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AUTO_HIDE_CONTROLS] ?: true
        }

    override val defaultGridColumns: Flow<Int> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val cols = preferences[PreferencesKeys.GRID_COLUMNS] ?: 3
            if (cols in 3..4) cols else 3
        }

    override val themeMode: Flow<ThemeMode> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val themeName = preferences[PreferencesKeys.THEME_MODE]
            try {
                if (themeName != null) ThemeMode.valueOf(themeName) else ThemeMode.SYSTEM
            } catch (_: IllegalArgumentException) {
                ThemeMode.SYSTEM
            }
        }

    override suspend fun setXdrDisplayEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.XDR_DISPLAY_ENABLED] = enabled
        }
    }

    override suspend fun setAutoHideControlsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_HIDE_CONTROLS] = enabled
        }
    }

    override suspend fun setDefaultGridColumns(columns: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GRID_COLUMNS] = columns.coerceIn(3, 4)
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = mode.name
        }
    }

    override suspend fun setMergeAlbums(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MERGE_ALBUMS] = enabled
        }
    }

    override suspend fun setSelectEssentialAlbums(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECT_ESSENTIAL_ALBUMS] = enabled
            if (enabled) {
                val current = preferences[PreferencesKeys.ESSENTIAL_ALBUM_NAMES]
                if (current.isNullOrEmpty()) {
                    preferences[PreferencesKeys.ESSENTIAL_ALBUM_NAMES] = getDiscoveredOrFallbackAlbumNames()
                }
            }
        }
    }

    override suspend fun setEssentialAlbumNames(names: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ESSENTIAL_ALBUM_NAMES] = names
        }
    }

    override suspend fun toggleEssentialAlbum(albumName: String) {
        val trimmed = albumName.trim()
        if (trimmed.isEmpty()) return
        context.dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.ESSENTIAL_ALBUM_NAMES]?.toMutableSet() ?: mutableSetOf()
            val existing = current.find { it.equals(trimmed, ignoreCase = true) }
            if (existing != null) {
                current.remove(existing)
            } else {
                current.add(trimmed)
            }
            preferences[PreferencesKeys.ESSENTIAL_ALBUM_NAMES] = current
        }
    }

    override val lastUpdateCheckMillis: Flow<Long> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.LAST_UPDATE_CHECK_MILLIS] ?: 0L
        }

    override val skippedVersionCode: Flow<Long> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SKIPPED_VERSION_CODE] ?: 0L
        }

    override val skippedVersionName: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SKIPPED_VERSION_NAME] ?: ""
        }

    override val remindLaterTimestampMillis: Flow<Long> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.REMIND_LATER_TIMESTAMP_MILLIS] ?: 0L
        }

    override suspend fun setLastUpdateCheckMillis(timeMillis: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_UPDATE_CHECK_MILLIS] = timeMillis
        }
    }

    override suspend fun setSkippedVersionCode(versionCode: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SKIPPED_VERSION_CODE] = versionCode
        }
    }

    override suspend fun setSkippedVersion(versionName: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SKIPPED_VERSION_NAME] = versionName
        }
    }

    override suspend fun setRemindLaterTimestamp(timestampMillis: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.REMIND_LATER_TIMESTAMP_MILLIS] = timestampMillis
        }
    }

    override suspend fun clearUpdateSuppression() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SKIPPED_VERSION_NAME] = ""
            preferences[PreferencesKeys.SKIPPED_VERSION_CODE] = 0L
            preferences[PreferencesKeys.REMIND_LATER_TIMESTAMP_MILLIS] = 0L
        }
    }

    private fun getDiscoveredOrFallbackAlbumNames(): Set<String> {
        val names = linkedSetOf<String>()
        try {
            val imageProjection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection,
                null,
                null,
                null
            )?.use { cursor ->
                val col = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                if (col != -1) {
                    while (cursor.moveToNext()) {
                        cursor.getString(col)?.trim()?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
                    }
                }
            }
            val videoProjection = arrayOf(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                null,
                null,
                null
            )?.use { cursor ->
                val col = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                if (col != -1) {
                    while (cursor.moveToNext()) {
                        cursor.getString(col)?.trim()?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}

        if (names.isEmpty()) {
            names.addAll(listOf("Camera", "Screenshots", "Downloads", "Pictures"))
        }
        return names
    }
}
