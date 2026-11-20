package com.gallery.feature.vault.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gallery.core.domain.model.VaultItem
import com.gallery.core.ui.components.GridScrollPosition
import com.gallery.core.domain.repository.VaultRepository
import com.gallery.core.domain.usecase.GetDefaultGridColumnsUseCase
import com.gallery.core.domain.usecase.GetVaultItemsUseCase
import com.gallery.core.domain.usecase.RestoreFromVaultUseCase
import com.gallery.core.domain.usecase.SetDefaultGridColumnsUseCase
import com.gallery.core.security.auth.AuthResult
import com.gallery.core.security.auth.BiometricAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    val previewBytes: Map<String, ByteArray> = emptyMap(),
    val corruptedItemIds: Set<String> = emptySet(),
    val activeViewingItem: VaultItem? = null,
    val activeViewingFile: File? = null,
    val decryptedFiles: Map<String, File> = emptyMap(),
    val isPreparingViewer: Boolean = false,
    val selectedItemIds: Set<String> = emptySet()
) {
    val isSelectionMode: Boolean get() = selectedItemIds.isNotEmpty()
    val selectedItems: List<VaultItem> get() = items.filter { it.id in selectedItemIds }
}

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
    val biometricAuthManager: BiometricAuthManager,
    getDefaultGridColumnsUseCase: GetDefaultGridColumnsUseCase,
    private val setDefaultGridColumnsUseCase: SetDefaultGridColumnsUseCase
) : AndroidViewModel(application) {

    companion object {
        private val _sessionAuthState = MutableStateFlow<VaultAuthState>(VaultAuthState.Idle)
    }

    val authState: StateFlow<VaultAuthState> = _sessionAuthState.asStateFlow()

    private val _vaultState = MutableStateFlow(VaultUiState())
    val vaultState: StateFlow<VaultUiState> = _vaultState.asStateFlow()

    private val _pinState = MutableStateFlow(PinState())
    val pinState: StateFlow<PinState> = _pinState.asStateFlow()

    val gridColumns: StateFlow<Int> = getDefaultGridColumnsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4)

    fun setGridColumns(columns: Int) {
        viewModelScope.launch {
            setDefaultGridColumnsUseCase(columns.coerceIn(2, 7))
        }
    }

    init {
        checkPinStatus()
        loadVaultItems()
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
            is AuthResult.Success -> _sessionAuthState.value = VaultAuthState.Authenticated
            is AuthResult.FallbackToPIN -> _sessionAuthState.value = VaultAuthState.PinEntry
            is AuthResult.Error -> _sessionAuthState.value = VaultAuthState.Error(result.message)
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
                        _sessionAuthState.value = VaultAuthState.Authenticated
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
                    _sessionAuthState.value = VaultAuthState.Authenticated
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

    private val gridScrollPositions = java.util.concurrent.ConcurrentHashMap<String, GridScrollPosition>()

    fun saveGridScrollPosition(key: String, index: Int, offset: Int) {
        gridScrollPositions[key] = GridScrollPosition(index.coerceAtLeast(0), offset.coerceAtLeast(0))
    }

    fun getGridScrollPosition(key: String): GridScrollPosition {
        return gridScrollPositions[key] ?: GridScrollPosition()
    }

    fun clearGridScrollPosition(key: String) {
        gridScrollPositions.remove(key)
    }

    fun selectTab(tab: VaultTab) {
        _vaultState.update { it.copy(selectedTab = tab, selectedAlbumName = null, selectedItemIds = emptySet()) }
    }

    fun openAlbum(albumName: String) {
        _vaultState.update { it.copy(selectedAlbumName = albumName, selectedItemIds = emptySet()) }
    }

    fun closeAlbum() {
        val albumName = _vaultState.value.selectedAlbumName
        if (albumName != null) {
            clearGridScrollPosition("vault_album_$albumName")
        }
        _vaultState.update { it.copy(selectedAlbumName = null, selectedItemIds = emptySet()) }
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

                    val itemIds = items.map { it.id }.toSet()
                    _vaultState.update { current ->
                        current.copy(
                            items = items,
                            albums = albums,
                            isLoading = false,
                            selectedItemIds = current.selectedItemIds.filter { it in itemIds }.toSet()
                        )
                    }
                }
        }
    }

    private val _isLaunchingEditor = MutableStateFlow(false)
    val isLaunchingEditor: StateFlow<Boolean> = _isLaunchingEditor.asStateFlow()

    fun setLaunchingEditor(launching: Boolean) {
        _isLaunchingEditor.value = launching
    }

    fun purgeSecureTempFiles() {
        try {
            val cacheFolder = File(getApplication<Application>().cacheDir, "vault_secure_temp")
            if (cacheFolder.exists()) {
                val files = cacheFolder.listFiles()
                files?.forEach { f ->
                    if (f.name != ".nomedia") {
                        try { f.delete() } catch (_: Exception) {}
                    }
                }
                val nomedia = File(cacheFolder, ".nomedia")
                if (!nomedia.exists()) nomedia.createNewFile()
            }
        } catch (_: Exception) {}
    }

    fun onLeaveVaultScreen() {
        purgeSecureTempFiles()
        _sessionAuthState.value = VaultAuthState.Idle
        _vaultState.update { it.copy(previewBytes = emptyMap(), decryptedFiles = emptyMap(), activeViewingItem = null, activeViewingFile = null, selectedItemIds = emptySet()) }
    }

    fun onAppBackgrounded() {
        if (_isLaunchingEditor.value) {
            return
        }
        _sessionAuthState.value = VaultAuthState.Idle
        _vaultState.update {
            it.copy(
                previewBytes = emptyMap(),
                previewFiles = emptyMap(),
                decryptedFiles = emptyMap(),
                activeViewingItem = null,
                activeViewingFile = null,
                selectedItemIds = emptySet()
            )
        }
        purgeSecureTempFiles()
    }

    private val activePreviewJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun requestPreview(item: VaultItem) {
        if (item.id in _vaultState.value.previewBytes ||
            item.id in _vaultState.value.corruptedItemIds ||
            activePreviewJobs.containsKey(item.id)) {
            return
        }

        val job = viewModelScope.launch(Dispatchers.IO) {
            val currentJob = coroutineContext[Job]
            try {
                // 1. Fetch encrypted thumbnail directly decrypted in memory as ByteArray (zero disk exposure)
                val thumbBytes = vaultRepository.getThumbnailBytes(item)
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    if (isActive) {
                        _vaultState.update { current ->
                            current.copy(
                                previewBytes = current.previewBytes + (item.id to thumbBytes)
                            )
                        }
                    }
                    return@launch
                }

                // 2. If thumbnail bytes unavailable, mark corrupted or retry
                if (isActive) {
                    _vaultState.update { current ->
                        current.copy(corruptedItemIds = current.corruptedItemIds + item.id)
                    }
                }
            } finally {
                if (currentJob != null) {
                    activePreviewJobs.remove(item.id, currentJob)
                }
            }
        }
        val existing = activePreviewJobs.putIfAbsent(item.id, job)
        if (existing != null) {
            job.cancel()
        }
    }

    fun releasePreview(itemId: String) {
        activePreviewJobs.remove(itemId)?.cancel()
        _vaultState.update { current ->
            current.copy(
                previewBytes = current.previewBytes - itemId,
                previewFiles = current.previewFiles - itemId
            )
        }
    }

    fun toggleSelection(itemId: String) {
        _vaultState.update { current ->
            val updated = if (itemId in current.selectedItemIds) {
                current.selectedItemIds - itemId
            } else {
                current.selectedItemIds + itemId
            }
            current.copy(selectedItemIds = updated)
        }
    }

    fun selectAll(targetItems: List<VaultItem>) {
        _vaultState.update { current ->
            val targetIds = targetItems.map { it.id }.toSet()
            val updated = if (current.selectedItemIds.containsAll(targetIds)) {
                emptySet()
            } else {
                targetIds
            }
            current.copy(selectedItemIds = updated)
        }
    }

    fun clearSelection() {
        _vaultState.update { it.copy(selectedItemIds = emptySet()) }
    }

    fun openViewer(item: VaultItem, allItems: List<VaultItem> = _vaultState.value.items) {
        val currentIndex = allItems.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        _vaultState.update { it.copy(activeViewingItem = item, selectedItemIds = emptySet()) }
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
        _vaultState.update { it.copy(activeViewingItem = null, activeViewingFile = null, decryptedFiles = emptyMap()) }
        purgeSecureTempFiles()
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
                        previewBytes = it.previewBytes - item.id,
                        previewFiles = it.previewFiles - item.id,
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
                    previewBytes = it.previewBytes - item.id,
                    previewFiles = it.previewFiles - item.id,
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

    fun restoreSelectedItems() {
        val targets = _vaultState.value.selectedItems
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _vaultState.update { it.copy(isLoading = true) }
            var successCount = 0
            val restoredIds = mutableSetOf<String>()
            for (item in targets) {
                val result = restoreFromVaultUseCase(item)
                if (result.isSuccess) {
                    successCount++
                    restoredIds.add(item.id)
                }
            }
            _vaultState.update { current ->
                current.copy(
                    isLoading = false,
                    selectedItemIds = emptySet(),
                    previewBytes = current.previewBytes - restoredIds,
                    previewFiles = current.previewFiles - restoredIds,
                    decryptedFiles = current.decryptedFiles - restoredIds,
                    corruptedItemIds = current.corruptedItemIds - restoredIds,
                    infoMessage = when {
                        successCount == targets.size -> "$successCount item(s) taken out of Secure Folder and restored to Gallery"
                        successCount > 0 -> "Restored $successCount of ${targets.size} item(s) to Gallery"
                        else -> "Failed to restore selected items"
                    }
                )
            }
        }
    }

    fun removeSelectedFromVault() {
        val targets = _vaultState.value.selectedItems
        if (targets.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _vaultState.update { it.copy(isLoading = true) }
            val targetIds = targets.map { it.id }.toSet()
            val updatedItems = _vaultState.value.items.filter { it.id !in targetIds }
            val updatedAlbums = updatedItems.groupBy { it.albumName.ifBlank { "Default" } }
                .map { (name, albumItems) ->
                    VaultAlbum(
                        name = name,
                        items = albumItems,
                        coverItem = albumItems.resolveFirstCoverItem()
                    )
                }
                .sortedByDescending { it.items.size }

            _vaultState.update { current ->
                current.copy(
                    isLoading = false,
                    items = updatedItems,
                    albums = updatedAlbums,
                    selectedItemIds = emptySet(),
                    previewBytes = current.previewBytes - targetIds,
                    previewFiles = current.previewFiles - targetIds,
                    decryptedFiles = current.decryptedFiles - targetIds,
                    corruptedItemIds = current.corruptedItemIds - targetIds,
                    infoMessage = "Removed ${targets.size} item(s) from Vault"
                )
            }

            for (item in targets) {
                vaultRepository.removeFromVault(item)
            }
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
        purgeSecureTempFiles()
    }
}
