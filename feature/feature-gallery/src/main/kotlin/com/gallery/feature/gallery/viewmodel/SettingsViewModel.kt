package com.gallery.feature.gallery.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.view.Display
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gallery.core.domain.model.Album
import com.gallery.core.domain.model.AppUpdateInfo
import com.gallery.core.domain.model.AppUpdateState
import com.gallery.core.domain.model.ThemeMode
import com.gallery.core.domain.repository.AppUpdateRepository
import com.gallery.core.domain.repository.SettingsRepository
import com.gallery.core.domain.usecase.GetAlbumsUseCase
import com.gallery.core.domain.usecase.GetAutoHideControlsSettingUseCase
import com.gallery.core.domain.usecase.GetDefaultGridColumnsUseCase
import com.gallery.core.domain.usecase.GetThemeModeUseCase
import com.gallery.core.domain.usecase.GetXdrDisplaySettingUseCase
import com.gallery.core.domain.usecase.SetAutoHideControlsSettingUseCase
import com.gallery.core.domain.usecase.SetDefaultGridColumnsUseCase
import com.gallery.core.domain.usecase.SetThemeModeUseCase
import com.gallery.core.domain.usecase.SetXdrDisplaySettingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PermissionsLiveStatus(
    val hasPhotosVideosAccess: Boolean,
    val isPartialVisualAccess: Boolean,
    val hasImagesPermission: Boolean,
    val hasVideosPermission: Boolean,
    val hasVisualSelectedPermission: Boolean,
    val hasLegacyStoragePermission: Boolean,
    val hasAllFilesAccess: Boolean,
    val photosVideosStatusText: String,
    val allFilesStatusText: String
)

data class AppAboutInfo(
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val applicationId: String,
    val buildType: String
)

data class DisplayHdrInfo(
    val isHdrSupported: Boolean,
    val isWideColorGamutSupported: Boolean,
    val supportedHdrTypes: List<String>,
    val androidVersion: String,
    val isAndroid14OrHigher: Boolean,
    val isAndroid15OrHigher: Boolean
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    getXdrDisplaySettingUseCase: GetXdrDisplaySettingUseCase,
    private val setXdrDisplaySettingUseCase: SetXdrDisplaySettingUseCase,
    getAutoHideControlsSettingUseCase: GetAutoHideControlsSettingUseCase,
    private val setAutoHideControlsSettingUseCase: SetAutoHideControlsSettingUseCase,
    getDefaultGridColumnsUseCase: GetDefaultGridColumnsUseCase,
    private val setDefaultGridColumnsUseCase: SetDefaultGridColumnsUseCase,
    getThemeModeUseCase: GetThemeModeUseCase,
    private val setThemeModeUseCase: SetThemeModeUseCase,
    private val settingsRepository: SettingsRepository,
    getAlbumsUseCase: GetAlbumsUseCase,
    private val appUpdateRepository: AppUpdateRepository
) : ViewModel() {

    val updateState: StateFlow<AppUpdateState> = appUpdateRepository.updateState

    val isXdrEnabled: StateFlow<Boolean> = getXdrDisplaySettingUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isAutoHideControls: StateFlow<Boolean> = getAutoHideControlsSettingUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val gridColumns: StateFlow<Int> = getDefaultGridColumnsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4)

    val themeMode: StateFlow<ThemeMode> = getThemeModeUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val mergeAlbums: StateFlow<Boolean> = settingsRepository.settings
        .map { it.mergeAlbums }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val selectEssentialAlbums: StateFlow<Boolean> = settingsRepository.settings
        .map { it.selectEssentialAlbums }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val essentialAlbumNames: StateFlow<Set<String>> = settingsRepository.settings
        .map { it.essentialAlbumNames }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val deviceAlbums: StateFlow<List<Album>> = getAlbumsUseCase(filterEssential = false)
        .map { list -> list.filter { !it.isCustom && !it.isSmartAlbum } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _displayHdrInfo = MutableStateFlow(inspectDisplayHdr())
    val displayHdrInfo: StateFlow<DisplayHdrInfo> = _displayHdrInfo.asStateFlow()

    private val _hasAllFilesAccess = MutableStateFlow(checkAllFilesAccess())
    val hasAllFilesAccess: StateFlow<Boolean> = _hasAllFilesAccess.asStateFlow()

    private val _permissionsStatus = MutableStateFlow(checkPermissionsStatus())
    val permissionsStatus: StateFlow<PermissionsLiveStatus> = _permissionsStatus.asStateFlow()

    val aboutInfo: AppAboutInfo = loadAboutInfo()

    fun setXdrEnabled(enabled: Boolean) {
        viewModelScope.launch {
            setXdrDisplaySettingUseCase(enabled)
        }
    }

    fun setAutoHideControls(enabled: Boolean) {
        viewModelScope.launch {
            setAutoHideControlsSettingUseCase(enabled)
        }
    }

    fun setDefaultGridColumns(columns: Int) {
        viewModelScope.launch {
            setDefaultGridColumnsUseCase(columns)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            setThemeModeUseCase(mode)
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            appUpdateRepository.checkForUpdates(isManual = true)
        }
    }

    fun downloadUpdate(info: AppUpdateInfo) {
        viewModelScope.launch {
            appUpdateRepository.downloadUpdate(info)
        }
    }

    fun cancelDownload() {
        appUpdateRepository.cancelDownload()
    }

    fun skipVersion(versionName: String) {
        viewModelScope.launch {
            appUpdateRepository.skipVersion(versionName)
        }
    }

    fun skipVersion(versionCode: Long) {
        viewModelScope.launch {
            appUpdateRepository.skipVersion(versionCode)
        }
    }

    fun remindMeLater() {
        viewModelScope.launch {
            appUpdateRepository.remindMeLater()
        }
    }

    fun dismissUpdateState() {
        appUpdateRepository.dismissUpdateState()
    }

    fun setMergeAlbums(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setMergeAlbums(enabled)
        }
    }

    fun setSelectEssentialAlbums(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSelectEssentialAlbums(enabled)
        }
    }

    fun setEssentialAlbumNames(names: Set<String>) {
        viewModelScope.launch {
            settingsRepository.setEssentialAlbumNames(names)
        }
    }

    fun toggleEssentialAlbum(albumName: String) {
        viewModelScope.launch {
            settingsRepository.toggleEssentialAlbum(albumName)
        }
    }

    fun selectAllEssentialAlbums() {
        viewModelScope.launch {
            val all = deviceAlbums.value.map { it.name }.toSet()
            settingsRepository.setEssentialAlbumNames(all)
        }
    }

    fun clearEssentialAlbums() {
        viewModelScope.launch {
            settingsRepository.setEssentialAlbumNames(emptySet())
        }
    }

    fun refreshStoragePermission() {
        _hasAllFilesAccess.value = checkAllFilesAccess()
        _permissionsStatus.value = checkPermissionsStatus()
    }

    private fun checkPermissionsStatus(): PermissionsLiveStatus {
        val hasAllFiles = checkAllFilesAccess()

        val hasImages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        val hasVideos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        val hasVisualSelected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        val hasLegacyStorage = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        val hasFullPhotosVideos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasImages && hasVideos
        } else {
            hasLegacyStorage
        }

        val isPartial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hasVisualSelected && !hasFullPhotosVideos
        } else {
            false
        }

        val photosSummary = when {
            hasFullPhotosVideos -> "Full Access Granted"
            isPartial -> "Partial Access (Selected Photos Only)"
            else -> "Access Required"
        }

        val allFilesSummary = if (hasAllFiles) "Full Access Granted" else "Standard (Scoped Storage)"

        return PermissionsLiveStatus(
            hasPhotosVideosAccess = hasFullPhotosVideos || isPartial,
            isPartialVisualAccess = isPartial,
            hasImagesPermission = hasImages,
            hasVideosPermission = hasVideos,
            hasVisualSelectedPermission = hasVisualSelected,
            hasLegacyStoragePermission = hasLegacyStorage,
            hasAllFilesAccess = hasAllFiles,
            photosVideosStatusText = photosSummary,
            allFilesStatusText = allFilesSummary
        )
    }

    private fun loadAboutInfo(): AppAboutInfo {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val vName = packageInfo.versionName ?: "1.0.0"
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            AppAboutInfo(
                appName = "Gallery",
                versionName = vName,
                versionCode = vCode,
                applicationId = context.packageName,
                buildType = "Release / Local-First"
            )
        } catch (_: Exception) {
            AppAboutInfo(
                appName = "Gallery",
                versionName = "1.0.0",
                versionCode = 1L,
                applicationId = context.packageName,
                buildType = "Release / Local-First"
            )
        }
    }

    private fun checkAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun inspectDisplayHdr(): DisplayHdrInfo {
        return try {
            val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            }

            val isHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                display?.isHdr == true
            } else {
                false
            }

            val isWideColorGamut = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                display?.isWideColorGamut == true
            } else {
                false
            }

            val hdrTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val types = display?.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
                types.toList().mapNotNull { type: Int ->
                    when (type) {
                        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                        else -> null
                    }
                }
            } else {
                emptyList<String>()
            }

            DisplayHdrInfo(
                isHdrSupported = isHdr,
                isWideColorGamutSupported = isWideColorGamut,
                supportedHdrTypes = hdrTypes,
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                isAndroid14OrHigher = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                isAndroid15OrHigher = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
            )
        } catch (_: Throwable) {
            DisplayHdrInfo(
                isHdrSupported = false,
                isWideColorGamutSupported = false,
                supportedHdrTypes = emptyList(),
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                isAndroid14OrHigher = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                isAndroid15OrHigher = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
            )
        }
    }
}
