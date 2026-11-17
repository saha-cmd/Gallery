package com.gallery.feature.vault.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gallery.core.domain.model.VaultItem
import com.gallery.core.domain.repository.VaultRepository
import com.gallery.core.domain.usecase.GetVaultItemsUseCase
import com.gallery.core.domain.usecase.RestoreFromVaultUseCase
import com.gallery.core.security.auth.AuthResult
import com.gallery.core.security.auth.BiometricAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

sealed class VaultAuthState {
    object Idle : VaultAuthState()
    object Authenticated : VaultAuthState()
    object PinEntry : VaultAuthState()
    data class Error(val message: String) : VaultAuthState()
}

enum class VaultTab {
    ALL_MEDIA,
    ALBUMS
}

data class VaultAlbum(
    val name: String,
    val items: List<VaultItem>,
    val coverItem: VaultItem?
)

fun List<VaultItem>.resolveFirstCoverItem(): VaultItem? =
    firstOrNull { !it.isVideo } ?: firstOrNull()

fun List<VaultItem>.resolveLastCoverItem(): VaultItem? =
    lastOrNull { !it.isVideo } ?: lastOrNull()

data class VaultUiState(
    val items: List<VaultItem> = emptyList(),
    val albums: List<VaultAlbum> = emptyList(),
    val selectedTab: VaultTab = VaultTab.ALL_MEDIA,
    val selectedAlbumName: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val infoMessage: String? = null,
    val previewFiles: Map<String, File> = emptyMap(),
    val previewBitmaps: Map<String, Bitmap> = emptyMap(),
    val corruptedItemIds: Set<String> = emptySet(),
    val activeViewingItem: VaultItem? = null,
    val activeViewingFile: File? = null,
    val decryptedFiles: Map<String, File> = emptyMap(),
    val isPreparingViewer: Boolean = false
)

data class PinState(
    val pin: String = "",
    val isFirstTimeSetup: Boolean = false,
    val isConfirming: Boolean = false,
    val initialNewPin: String = "",
    val error: String? = null
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    application: Application,
    private val getVaultItemsUseCase: GetVaultItemsUseCase,
    private val restoreFromVaultUseCase: RestoreFromVaultUseCase,
    private val vaultRepository: VaultRepository,
    val biometricAuthManager: BiometricAuthManager
) : AndroidViewModel(application) {

    private val _authState = MutableStateFlow<VaultAuthState>(VaultAuthState.Idle)
    val authState: StateFlow<VaultAuthState> = _authState.asStateFlow()

    private val _vaultState = MutableStateFlow(VaultUiState())
    val vaultState: StateFlow<VaultUiState> = _vaultState.asStateFlow()

    private val _pinState = MutableStateFlow(PinState())
    val pinState: StateFlow<PinState> = _pinState.asStateFlow()

    init {
        checkPinStatus()
    }

    fun checkPinStatus() {
        viewModelScope.launch {
            val hasPin = vaultRepository.hasPin()
            _pinState.update {
                it.copy(
                    isFirstTimeSetup = !hasPin,
                    isConfirming = false,
                    pin = "",
                    error = null
                )
            }
        }
    }

    suspend fun hasExistingPin(): Boolean = vaultRepository.hasPin()

    fun onBiometricResult(result: AuthResult) {
        when (result) {
            is AuthResult.Success -> _authState.value = VaultAuthState.Authenticated
            is AuthResult.FallbackToPIN -> _authState.value = VaultAuthState.PinEntry
            is AuthResult.Error -> _authState.value = VaultAuthState.Error(result.message)
        }
    }

    fun onPinDigitEntered(digit: String) {
        val current = _pinState.value.pin
        if (current.length < 6) {
            val updatedPin = current + digit
            _pinState.update { it.copy(pin = updatedPin, error = null) }
            if (updatedPin.length == 6) {
                handlePinComplete(updatedPin)
            }
        }
    }

    private fun handlePinComplete(enteredPin: String) {
        val state = _pinState.value
        viewModelScope.launch {
            if (state.isFirstTimeSetup) {
                if (!state.isConfirming) {
                    // Step 1: Save first entered PIN and prompt for confirmation
                    _pinState.update {
                        it.copy(
                            isConfirming = true,
                            initialNewPin = enteredPin,
                            pin = "",
                            error = null
                        )
                    }
                } else {
                    // Step 2: Verify confirmation matches
                    if (enteredPin == state.initialNewPin) {
                        vaultRepository.setPin(enteredPin)
                        _pinState.update {
                            it.copy(
                                isFirstTimeSetup = false,
                                isConfirming = false,
                                pin = "",
                                error = null
                            )
                        }
                        _authState.value = VaultAuthState.Authenticated
                    } else {
                        _pinState.update {
                            it.copy(
                                isConfirming = false,
                                initialNewPin = "",
                                pin = "",
                                error = "PINs did not match. Please start again."
                            )
                        }
                    }
                }
            } else {
                // Normal unlock
                val correct = vaultRepository.verifyPin(enteredPin)
                if (correct) {
                    _authState.value = VaultAuthState.Authenticated
                } else {
                    _pinState.update { it.copy(pin = "", error = "Incorrect PIN") }
                }
            }
        }
    }

    fun onPinDelete() {
        val current = _pinState.value.pin
        if (current.isNotEmpty()) {
            _pinState.update { it.copy(pin = current.dropLast(1), error = null) }
        }
    }

    fun submitPin() {
        val current = _pinState.value.pin
        if (current.length == 6) {
            handlePinComplete(current)
        }
    }

    fun changePin(currentPin: String, newPin: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            if (!vaultRepository.verifyPin(currentPin)) {
                onResult(false, "Current PIN is incorrect")
                return@launch
            }
            if (newPin.length != 6) {
                onResult(false, "New PIN must be 6 digits")
                return@launch
            }
            vaultRepository.setPin(newPin)
            onResult(true, "PIN updated successfully")
        }
    }

    fun selectTab(tab: VaultTab) {
        _vaultState.update { it.copy(selectedTab = tab, selectedAlbumName = null) }
    }

    fun openAlbum(albumName: String) {
        _vaultState.update { it.copy(selectedAlbumName = albumName) }
    }

    fun closeAlbum() {
        _vaultState.update { it.copy(selectedAlbumName = null) }
    }

    fun loadVaultItems() {
        viewModelScope.launch {
            getVaultItemsUseCase()
                .catch { e -> _vaultState.update { it.copy(error = e.message, isLoading = false) } }
                .collect { items ->
                    val albums = items.groupBy { it.albumName.ifBlank { "Default" } }
                        .map { (name, albumItems) ->
                            VaultAlbum(
                                name = name,
                                items = albumItems,
                                coverItem = albumItems.resolveFirstCoverItem()
                            )
                        }
                        .sortedByDescending { it.items.size }

                    _vaultState.update { it.copy(items = items, albums = albums, isLoading = false) }
                }
        }
    }

    private val pendingPreviewItemIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun requestPreview(item: VaultItem) {
        if (item.id in _vaultState.value.previewBitmaps ||
            item.id in _vaultState.value.previewFiles ||
            item.id in _vaultState.value.corruptedItemIds ||
            !pendingPreviewItemIds.add(item.id)) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Check if pre-cached thumbnail file exists (instant load)
                val cachedThumb = vaultRepository.getThumbnailFile(item)
                if (cachedThumb != null && cachedThumb.exists() && cachedThumb.length() > 0) {
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bmp = BitmapFactory.decodeFile(cachedThumb.absolutePath, options)
                    if (bmp != null) {
                        _vaultState.update { current ->
                            current.copy(
                                previewBitmaps = current.previewBitmaps + (item.id to bmp),
                                previewFiles = current.previewFiles + (item.id to cachedThumb)
                            )
                        }
                        return@launch
                    }
                }

                // 2. Decrypt to temp file and pre-cache thumbnail (160x160, RGB_565)
                vaultRepository.getDecryptedTempFile(item).onSuccess { file ->
                    val thumbDir = File(getApplication<Application>().filesDir, "vault/thumbnails").apply { mkdirs() }
                    val thumbFile = File(thumbDir, "${item.id}.jpg")
                    val bitmap: Bitmap? = if (item.isVideo) {
                        try {
                            val retriever = MediaMetadataRetriever()
                            retriever.setDataSource(file.absolutePath)
                            val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                                retriever.getScaledFrameAtTime(
                                    1_000_000,
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    160,
                                    160
                                ) ?: retriever.frameAtTime
                            } else {
                                retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    ?: retriever.frameAtTime
                            }
                            retriever.release()
                            frame?.also {
                                thumbFile.outputStream().use { out -> it.compress(Bitmap.CompressFormat.JPEG, 80, out) }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("VaultViewModel", "Video frame extraction error", e)
                            null
                        }
                    } else {
                        try {
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.absolutePath, options)
                            var sampleSize = 1
                            if (options.outWidth > 0 && options.outHeight > 0) {
                                while (options.outWidth / (sampleSize * 2) >= 160 && options.outHeight / (sampleSize * 2) >= 160) {
                                    sampleSize *= 2
                                }
                            }
                            val decodeOptions = BitmapFactory.Options().apply {
                                inSampleSize = sampleSize
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                            decoded?.also {
                                thumbFile.outputStream().use { out -> it.compress(Bitmap.CompressFormat.JPEG, 80, out) }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("VaultViewModel", "Photo decode error", e)
                            null
                        }
                    }

                    _vaultState.update { current ->
                        current.copy(
                            previewFiles = current.previewFiles + (item.id to (if (thumbFile.exists()) thumbFile else file)),
                            previewBitmaps = if (bitmap != null) current.previewBitmaps + (item.id to bitmap) else current.previewBitmaps
                        )
                    }
                }.onFailure { err ->
                    android.util.Log.e("VaultViewModel", "Decryption failed for item ${item.id}", err)
                    _vaultState.update { current ->
                        current.copy(corruptedItemIds = current.corruptedItemIds + item.id)
                    }
                }
            } finally {
                pendingPreviewItemIds.remove(item.id)
            }
        }
    }

    fun openViewer(item: VaultItem, allItems: List<VaultItem> = _vaultState.value.items) {
        val currentIndex = allItems.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        _vaultState.update { it.copy(activeViewingItem = item) }
        prepareViewingFiles(allItems, currentIndex)
    }

    fun prepareViewingFiles(items: List<VaultItem>, currentIndex: Int) {
        val currentItem = items.getOrNull(currentIndex) ?: return
        val cachedCurrent = _vaultState.value.decryptedFiles[currentItem.id]
        if (cachedCurrent != null && cachedCurrent.exists()) {
            _vaultState.update { it.copy(activeViewingItem = currentItem, activeViewingFile = cachedCurrent, isPreparingViewer = false) }
        } else {
            _vaultState.update { it.copy(activeViewingItem = currentItem, isPreparingViewer = true) }
            viewModelScope.launch(Dispatchers.IO) {
                val result = vaultRepository.getDecryptedTempFile(currentItem)
                result.onSuccess { file ->
                    _vaultState.update { it.copy(activeViewingFile = file, decryptedFiles = it.decryptedFiles + (currentItem.id to file), isPreparingViewer = false) }
                }.onFailure { err ->
                    _vaultState.update { it.copy(isPreparingViewer = false, error = "Failed to decrypt: ${err.message}") }
                }
            }
        }

        // Pre-warm currentIndex - 1 and currentIndex + 1
        val neighborIndices = listOf(currentIndex - 1, currentIndex + 1)
        for (idx in neighborIndices) {
            val neighbor = items.getOrNull(idx) ?: continue
            val existingNeighbor = _vaultState.value.decryptedFiles[neighbor.id]
            if (existingNeighbor == null || !existingNeighbor.exists()) {
                viewModelScope.launch(Dispatchers.IO) {
                    vaultRepository.getDecryptedTempFile(neighbor).onSuccess { file ->
                        _vaultState.update { it.copy(decryptedFiles = it.decryptedFiles + (neighbor.id to file)) }
                    }
                }
            }
        }
    }

    fun prepareViewingFile(item: VaultItem) {
        val items = _vaultState.value.items
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            prepareViewingFiles(items, idx)
        } else {
            if (_vaultState.value.activeViewingItem?.id == item.id && _vaultState.value.activeViewingFile != null) return
            viewModelScope.launch(Dispatchers.IO) {
                _vaultState.update { it.copy(activeViewingItem = item, isPreparingViewer = true) }
                val result = vaultRepository.getDecryptedTempFile(item)
                result.onSuccess { file ->
                    _vaultState.update { it.copy(activeViewingFile = file, decryptedFiles = it.decryptedFiles + (item.id to file), isPreparingViewer = false) }
                }.onFailure { err ->
                    _vaultState.update { it.copy(isPreparingViewer = false, error = "Failed to decrypt: ${err.message}") }
                }
            }
        }
    }

    fun closeViewer() {
        _vaultState.update { it.copy(activeViewingItem = null, activeViewingFile = null) }
    }

    fun restoreItemToGallery(item: VaultItem) {
        viewModelScope.launch {
            _vaultState.update { it.copy(isLoading = true) }
            val result = restoreFromVaultUseCase(item)
            result.onSuccess { uri ->
                closeViewer()
                _vaultState.update {
                    it.copy(
                        isLoading = false,
                        previewFiles = it.previewFiles - item.id,
                        previewBitmaps = it.previewBitmaps - item.id,
                        decryptedFiles = it.decryptedFiles - item.id,
                        corruptedItemIds = it.corruptedItemIds - item.id,
                        infoMessage = "${item.originalName} taken out of Secure Folder and restored to Gallery"
                    )
                }
            }.onFailure { err ->
                _vaultState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to restore: ${err.message}"
                    )
                }
            }
        }
    }

    fun removeFromVault(item: VaultItem) {
        viewModelScope.launch {
            val isCurrentViewing = _vaultState.value.activeViewingItem?.id == item.id
            val inAlbum = _vaultState.value.selectedAlbumName != null
            val currentDisplayed = if (inAlbum) {
                _vaultState.value.items.filter { it.albumName == _vaultState.value.selectedAlbumName }
            } else {
                _vaultState.value.items
            }
            val remainingDisplayed = currentDisplayed.filter { it.id != item.id }

            val nextItem = if (isCurrentViewing) {
                if (remainingDisplayed.isEmpty()) {
                    null
                } else {
                    val deletedIndex = currentDisplayed.indexOfFirst { it.id == item.id }
                    if (deletedIndex in remainingDisplayed.indices) {
                        remainingDisplayed[deletedIndex]
                    } else {
                        remainingDisplayed.last()
                    }
                }
            } else {
                _vaultState.value.activeViewingItem
            }

            val updatedItems = _vaultState.value.items.filter { it.id != item.id }
            val updatedAlbums = updatedItems.groupBy { it.albumName.ifBlank { "Default" } }
                .map { (name, albumItems) ->
                    VaultAlbum(
                        name = name,
                        items = albumItems,
                        coverItem = albumItems.resolveFirstCoverItem()
                    )
                }
                .sortedByDescending { it.items.size }

            _vaultState.update {
                it.copy(
                    items = updatedItems,
                    albums = updatedAlbums,
                    activeViewingItem = nextItem,
                    activeViewingFile = if (nextItem != null) it.decryptedFiles[nextItem.id] else null,
                    previewFiles = it.previewFiles - item.id,
                    previewBitmaps = it.previewBitmaps - item.id,
                    decryptedFiles = it.decryptedFiles - item.id,
                    corruptedItemIds = it.corruptedItemIds - item.id,
                    infoMessage = "${item.originalName} removed from Vault"
                )
            }

            if (nextItem != null) {
                prepareViewingFile(nextItem)
            }

            vaultRepository.removeFromVault(item)
        }
    }

    fun clearCorruptedItems() {
        viewModelScope.launch {
            _vaultState.update { it.copy(isLoading = true) }
            val count = vaultRepository.clearCorruptedItems()
            _vaultState.update {
                it.copy(
                    isLoading = false,
                    corruptedItemIds = emptySet(),
                    infoMessage = if (count > 0) "Removed $count damaged/corrupted item(s)" else "No corrupted items found"
                )
            }
        }
    }

    fun dismissMessages() {
        _vaultState.update { it.copy(error = null, infoMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            val cacheFolder = File(getApplication<Application>().cacheDir, "vault_secure_temp")
            if (cacheFolder.exists()) {
                cacheFolder.deleteRecursively()
            }
        } catch (_: Exception) {}
    }
}
