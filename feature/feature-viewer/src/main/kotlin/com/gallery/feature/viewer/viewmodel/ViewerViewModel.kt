package com.gallery.feature.viewer.viewmodel

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gallery.core.data.repository.MediaRepositoryImpl
import com.gallery.core.domain.model.Album
import com.gallery.core.domain.model.MediaItem
import com.gallery.core.domain.model.MoveAlbumResult
import com.gallery.core.domain.repository.SettingsRepository
import com.gallery.core.domain.usecase.AddItemsToCustomAlbumUseCase
import com.gallery.core.domain.usecase.AddToVaultUseCase
import com.gallery.core.domain.usecase.CreateCustomAlbumUseCase
import com.gallery.core.domain.usecase.DeleteMediaUseCase
import com.gallery.core.domain.usecase.GetAlbumsUseCase
import com.gallery.core.domain.usecase.GetAllMediaUseCase
import com.gallery.core.domain.usecase.GetAutoHideControlsSettingUseCase
import com.gallery.core.domain.usecase.GetVaultAlbumNamesUseCase
import com.gallery.core.domain.usecase.GetXdrDisplaySettingUseCase
import com.gallery.core.domain.usecase.MoveMediaToAlbumUseCase
import com.gallery.core.domain.usecase.MoveToRecycleBinUseCase
import com.gallery.core.domain.usecase.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class ViewerUiState(
    val mediaList: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val pendingDeleteIntentSender: IntentSender? = null,
    val pendingMoveIntentSender: IntentSender? = null,
    val pendingMoveItem: MediaItem? = null,
    val pendingTargetAlbum: Album? = null,
    val pendingNewAlbumName: String? = null,
    val infoMessage: String? = null,
    val itemMovedToVault: Boolean = false,
    val itemDeleted: Boolean = false,
    val itemMovedOut: Boolean = false
)

@HiltViewModel
class ViewerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val getAllMediaUseCase: GetAllMediaUseCase,
    private val getAlbumsUseCase: GetAlbumsUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val addItemsToCustomAlbumUseCase: AddItemsToCustomAlbumUseCase,
    private val createCustomAlbumUseCase: CreateCustomAlbumUseCase,
    private val addToVaultUseCase: AddToVaultUseCase,
    private val getVaultAlbumNamesUseCase: GetVaultAlbumNamesUseCase,
    private val deleteMediaUseCase: DeleteMediaUseCase,
    private val moveToRecycleBinUseCase: MoveToRecycleBinUseCase,
    private val moveMediaToAlbumUseCase: MoveMediaToAlbumUseCase,
    getXdrDisplaySettingUseCase: GetXdrDisplaySettingUseCase,
    getAutoHideControlsSettingUseCase: GetAutoHideControlsSettingUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    val vaultAlbums: StateFlow<List<String>> = getVaultAlbumNamesUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customAlbums: StateFlow<List<Album>> = getAlbumsUseCase()
        .map { albums -> albums.filter { it.isCustom } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albums: StateFlow<List<Album>> = getAlbumsUseCase(filterEssential = false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isXdrSettingEnabled: StateFlow<Boolean> = getXdrDisplaySettingUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isAutoHideControlsEnabled: StateFlow<Boolean> = getAutoHideControlsSettingUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _isXdrPreviewToggled = MutableStateFlow<Boolean?>(null)
    val isXdrActive: StateFlow<Boolean> = combine(
        isXdrSettingEnabled,
        _isXdrPreviewToggled
    ) { settingEnabled, previewToggled ->
        previewToggled ?: settingEnabled
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun toggleXdrPreview() {
        val currentSetting = isXdrSettingEnabled.value
        val currentActive = _isXdrPreviewToggled.value ?: currentSetting
        val nextState = !currentActive
        _isXdrPreviewToggled.value = nextState
        _uiState.update {
            it.copy(
                infoMessage = if (nextState) "XDR Ranging Active (Enhanced Dynamic Range)" else "Standard Dynamic Range (SDR)"
            )
        }
    }

    fun resetXdrPreview() {
        _isXdrPreviewToggled.value = null
    }

    init {
        val albumId: Long = savedStateHandle.get<Long>("albumId") ?: -1L
        viewModelScope.launch {
            combine(
                getAllMediaUseCase(),
                getAlbumsUseCase(filterEssential = false),
                settingsRepository.settings
            ) { allMedia, albums, settings ->
                when {
                    albumId == Album.ID_VIDEOS -> allMedia.filterIsInstance<MediaItem.Video>()
                    albumId == Album.ID_FAVORITES -> allMedia.filter { it.isFavorite }
                    albumId != Album.ID_ALL_MEDIA -> {
                        val targetAlbum = albums.find { it.id == albumId }
                        if (targetAlbum != null) {
                            if (settings.mergeAlbums && !targetAlbum.isCustom && !targetAlbum.isSmartAlbum) {
                                allMedia.filter { it.bucketName.trim().equals(targetAlbum.name.trim(), ignoreCase = true) }
                            } else {
                                targetAlbum.items
                            }
                        } else {
                            allMedia.filter { it.bucketId == albumId }
                        }
                    }
                    else -> allMedia
                }
            }.collect { filtered ->
                _uiState.update { it.copy(mediaList = filtered, isLoading = false) }
            }
        }
    }

    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch {
            toggleFavoriteUseCase(item.id)
        }
    }

    fun addToCustomAlbum(albumId: Long, item: MediaItem) {
        viewModelScope.launch {
            addItemsToCustomAlbumUseCase(albumId, listOf(item.id))
            _uiState.update { it.copy(infoMessage = "Added to album") }
        }
    }

    fun createAndAddToCustomAlbum(albumName: String, item: MediaItem) {
        viewModelScope.launch {
            createCustomAlbumUseCase(albumName, listOf(item.id))
            _uiState.update { it.copy(infoMessage = "Added to '$albumName'") }
        }
    }

    fun clearItemDeleted() {
        _uiState.update { it.copy(itemDeleted = false) }
    }

    fun clearPendingDeleteIntent() {
        _uiState.update { it.copy(pendingDeleteIntentSender = null) }
    }

    fun dismissInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    fun clearPendingMove() {
        _uiState.update {
            it.copy(
                pendingMoveIntentSender = null,
                pendingMoveItem = null,
                pendingTargetAlbum = null,
                pendingNewAlbumName = null
            )
        }
    }

    fun moveToAlbum(item: MediaItem, targetAlbum: Album?, newAlbumName: String?) {
        val albumId: Long = savedStateHandle.get<Long>("albumId") ?: -1L
        val sourceAlbumId = if (albumId != -1L && albumId != -2L) albumId else null

        viewModelScope.launch {
            val result = moveMediaToAlbumUseCase(listOf(item), targetAlbum, newAlbumName, sourceAlbumId)
            when (result) {
                is MoveAlbumResult.Success -> {
                    val targetName = result.targetAlbumName
                    val movedIdSet = result.movedIds.toSet()
                    val isViewingSpecificAlbum = albumId != Album.ID_ALL_MEDIA && albumId != Album.ID_VIDEOS

                    if (isViewingSpecificAlbum) {
                        val remaining = _uiState.value.mediaList.filter { it.id !in movedIdSet }
                        _uiState.update {
                            it.copy(
                                mediaList = remaining,
                                infoMessage = "Moved to $targetName",
                                itemMovedOut = true,
                                pendingMoveIntentSender = null,
                                pendingMoveItem = null,
                                pendingTargetAlbum = null,
                                pendingNewAlbumName = null
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                infoMessage = "Moved to $targetName",
                                pendingMoveIntentSender = null,
                                pendingMoveItem = null,
                                pendingTargetAlbum = null,
                                pendingNewAlbumName = null
                            )
                        }
                    }
                }
                is MoveAlbumResult.RequiresPermission -> {
                    _uiState.update {
                        it.copy(
                            pendingMoveIntentSender = result.intentSender,
                            pendingMoveItem = item,
                            pendingTargetAlbum = targetAlbum,
                            pendingNewAlbumName = newAlbumName
                        )
                    }
                }
                is MoveAlbumResult.Error -> {
                    _uiState.update {
                        it.copy(
                            infoMessage = "Failed to move: ${result.message}",
                            pendingMoveIntentSender = null,
                            pendingMoveItem = null,
                            pendingTargetAlbum = null,
                            pendingNewAlbumName = null
                        )
                    }
                }
            }
        }
    }

    fun retryPendingMove() {
        val item = _uiState.value.pendingMoveItem ?: return
        val targetAlbum = _uiState.value.pendingTargetAlbum
        val newAlbumName = _uiState.value.pendingNewAlbumName
        clearPendingMove()
        moveToAlbum(item, targetAlbum, newAlbumName)
    }

    fun moveToVault(item: MediaItem, albumName: String = "Default") {
        viewModelScope.launch {
            val mimeType = when (item) {
                is MediaItem.Photo -> item.mimeType
                is MediaItem.Video -> "video/*"
            }
            val result = withContext(Dispatchers.IO) {
                addToVaultUseCase(
                    sourceUri = item.uri,
                    originalName = item.name,
                    mimeType = mimeType,
                    isVideo = item is MediaItem.Video,
                    albumName = albumName
                )
            }

            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        pendingDeleteIntentSender = null,
                        itemMovedToVault = true
                    )
                }
            } else {
                _uiState.update {
                    it.copy(infoMessage = "Failed to secure: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    companion object {
        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    fun deleteItem(item: MediaItem) {
        // 1. Immediately hide media ID reactively across the whole app
        MediaRepositoryImpl.hideMediaIds(listOf(item.id))

        // 2. Immediately update uiState so viewer responds instantaneously without freezing
        _uiState.update { current ->
            val updatedList = current.mediaList.filter { it.id != item.id }
            current.copy(
                mediaList = updatedList,
                itemDeleted = false,
                infoMessage = "${item.name} moved to Recycle Bin"
            )
        }

        // 3. Dispatch the actual background archival onto application-scoped CoroutineScope
        applicationScope.launch {
            val mimeType = when (item) {
                is MediaItem.Photo -> item.mimeType
                is MediaItem.Video -> "video/*"
            }
            val result = moveToRecycleBinUseCase(
                sourceUri = item.uri,
                originalName = item.name,
                mimeType = mimeType,
                isVideo = item is MediaItem.Video,
                mediaId = item.id,
                bucketName = item.bucketName,
                dateAdded = item.dateAdded
            )

            if (result.isFailure) {
                MediaRepositoryImpl.unhideMediaIds(listOf(item.id))
                _uiState.update {
                    it.copy(
                        itemDeleted = false,
                        infoMessage = "Failed to move to Recycle Bin: ${result.exceptionOrNull()?.message}"
                    )
                }
            }
        }
    }
}
