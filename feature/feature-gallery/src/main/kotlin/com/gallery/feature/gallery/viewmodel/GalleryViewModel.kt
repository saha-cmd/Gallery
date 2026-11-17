package com.gallery.feature.gallery.viewmodel

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gallery.core.data.repository.MediaRepositoryImpl
import com.gallery.core.domain.model.Album
import com.gallery.core.domain.model.AppUpdateInfo
import com.gallery.core.domain.model.AppUpdateState
import com.gallery.core.domain.model.MediaItem
import com.gallery.core.domain.model.MoveAlbumResult
import com.gallery.core.domain.model.UserAlbumGroup
import com.gallery.core.domain.model.resolveFirstCoverUri
import com.gallery.core.domain.repository.AppUpdateRepository
import com.gallery.core.domain.repository.MediaRepository
import com.gallery.core.domain.usecase.AddAlbumsToUserGroupUseCase
import com.gallery.core.domain.usecase.AddItemsToCustomAlbumUseCase
import com.gallery.core.domain.usecase.AddToVaultUseCase
import com.gallery.core.domain.usecase.CreateCustomAlbumUseCase
import com.gallery.core.domain.usecase.CreateUserAlbumGroupUseCase
import com.gallery.core.domain.usecase.DeleteCustomAlbumUseCase
import com.gallery.core.domain.usecase.DeleteMediaUseCase
import com.gallery.core.domain.usecase.DeleteUserAlbumGroupUseCase
import com.gallery.core.domain.usecase.GetAlbumGroupsUseCase
import com.gallery.core.domain.usecase.GetAlbumsUseCase
import com.gallery.core.domain.usecase.GetAllMediaUseCase
import com.gallery.core.domain.usecase.GetDefaultGridColumnsUseCase
import com.gallery.core.domain.usecase.GetUserAlbumGroupsUseCase
import com.gallery.core.domain.usecase.GetVaultAlbumNamesUseCase
import com.gallery.core.domain.usecase.MoveMediaToAlbumUseCase
import com.gallery.core.domain.usecase.MoveToRecycleBinUseCase
import com.gallery.core.domain.usecase.RemoveAlbumsFromUserGroupUseCase
import com.gallery.core.domain.usecase.RemoveItemsFromCustomAlbumUseCase
import com.gallery.core.domain.usecase.RenameCustomAlbumUseCase
import com.gallery.core.domain.usecase.RenameUserAlbumGroupUseCase
import com.gallery.core.domain.usecase.SetAlbumGroupUseCase
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class MediaGroup(
    val dateLabel: String,
    val items: List<MediaItem>
)

private data class GalleryLoadedData(
    val media: List<MediaItem>,
    val mediaGroups: List<MediaGroup>,
    val albums: List<Album>,
    val albumGroups: List<AlbumGroupSection>,
    val userAlbumGroups: List<UserAlbumGroupUiModel>,
    val rawUserGroups: List<UserAlbumGroup>,
    val ungroupedAlbums: List<Album>,
    val userGroupOverrides: Map<String, String>,
    val videos: List<MediaItem.Video>
)

data class UserAlbumGroupUiModel(
    val id: Long,
    val name: String,
    val albums: List<Album>,
    val totalMediaCount: Int = albums.sumOf { it.itemCount },
    val albumKeys: Set<String> = albums.map { "album_${it.id}" }.toSet()
)

data class AlbumGroupSection(
    val title: String,
    val albums: List<Album>
)

enum class GalleryTab { TIMELINE, ALBUMS, VIDEOS }

data class GalleryUiState(
    val allMedia: List<MediaItem> = emptyList(),
    val mediaGroups: List<MediaGroup> = emptyList(),
    val albums: List<Album> = emptyList(),
    val albumGroups: List<AlbumGroupSection> = emptyList(),
    val userAlbumGroups: List<UserAlbumGroupUiModel> = emptyList(),
    val rawUserGroups: List<UserAlbumGroup> = emptyList(),
    val ungroupedAlbums: List<Album> = emptyList(),
    val userGroupOverrides: Map<String, String> = emptyMap(),
    val videos: List<MediaItem.Video> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val hasPermission: Boolean = false,
    val selectedItemIds: Set<Long> = emptySet(),
    val selectedAlbumIds: Set<Long> = emptySet(),
    val isPerformingBatchAction: Boolean = false,
    val batchActionMessage: String? = null,
    val pendingDeleteIntentSender: IntentSender? = null,
    val pendingMoveIntentSender: IntentSender? = null,
    val pendingMoveItems: List<MediaItem>? = null,
    val pendingTargetAlbum: Album? = null,
    val pendingNewAlbumName: String? = null,
    val pendingSourceAlbumId: Long? = null
) {
    val isSelectionMode: Boolean
        get() = selectedItemIds.isNotEmpty()

    val selectedItems: List<MediaItem>
        get() = allMedia.filter { it.id in selectedItemIds }

    val isAlbumSelectionMode: Boolean
        get() = selectedAlbumIds.isNotEmpty()

    val selectedAlbums: List<Album>
        get() = albums.filter { it.id in selectedAlbumIds }

    val customAlbums: List<Album>
        get() = albums.filter { it.isCustom }
}

@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val getAllMediaUseCase: GetAllMediaUseCase,
    private val getAlbumsUseCase: GetAlbumsUseCase,
    private val deleteMediaUseCase: DeleteMediaUseCase,
    private val addToVaultUseCase: AddToVaultUseCase,
    private val getVaultAlbumNamesUseCase: GetVaultAlbumNamesUseCase,
    private val moveToRecycleBinUseCase: MoveToRecycleBinUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val createCustomAlbumUseCase: CreateCustomAlbumUseCase,
    private val addItemsToCustomAlbumUseCase: AddItemsToCustomAlbumUseCase,
    private val removeItemsFromCustomAlbumUseCase: RemoveItemsFromCustomAlbumUseCase,
    private val renameCustomAlbumUseCase: RenameCustomAlbumUseCase,
    private val deleteCustomAlbumUseCase: DeleteCustomAlbumUseCase,
    private val setAlbumGroupUseCase: SetAlbumGroupUseCase,
    private val getAlbumGroupsUseCase: GetAlbumGroupsUseCase,
    private val getUserAlbumGroupsUseCase: GetUserAlbumGroupsUseCase,
    private val createUserAlbumGroupUseCase: CreateUserAlbumGroupUseCase,
    private val addAlbumsToUserGroupUseCase: AddAlbumsToUserGroupUseCase,
    private val removeAlbumsFromUserGroupUseCase: RemoveAlbumsFromUserGroupUseCase,
    private val renameUserAlbumGroupUseCase: RenameUserAlbumGroupUseCase,
    private val deleteUserAlbumGroupUseCase: DeleteUserAlbumGroupUseCase,
    private val moveMediaToAlbumUseCase: MoveMediaToAlbumUseCase,
    getDefaultGridColumnsUseCase: GetDefaultGridColumnsUseCase,
    private val appUpdateRepository: AppUpdateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    val updateState: StateFlow<AppUpdateState> = appUpdateRepository.updateState

    init {
        checkForStartupUpdates()
    }

    fun checkForStartupUpdates() {
        viewModelScope.launch {
            appUpdateRepository.checkForUpdates(isManual = false)
        }
    }

    fun downloadUpdate(info: AppUpdateInfo) {
        viewModelScope.launch {
            appUpdateRepository.downloadUpdate(info)
        }
    }

    fun cancelUpdateDownload() {
        appUpdateRepository.cancelDownload()
    }

    fun skipUpdateVersion(versionName: String) {
        viewModelScope.launch {
            appUpdateRepository.skipVersion(versionName)
        }
    }

    fun skipUpdateVersion(versionCode: Long) {
        viewModelScope.launch {
            appUpdateRepository.skipVersion(versionCode)
        }
    }

    fun remindMeLaterUpdate() {
        viewModelScope.launch {
            appUpdateRepository.remindMeLater()
        }
    }

    fun dismissUpdateState() {
        appUpdateRepository.dismissUpdateState()
    }

    val vaultAlbums: StateFlow<List<String>> = getVaultAlbumNamesUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gridColumns: StateFlow<Int> = getDefaultGridColumnsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)
    val selectedTab = MutableStateFlow(GalleryTab.TIMELINE)
    val selectedAlbum = MutableStateFlow<Album?>(null)
    val isGroupedView = MutableStateFlow(true)
    val collapsedGroups = MutableStateFlow<Set<String>>(emptySet())

    fun toggleGroupedView() {
        isGroupedView.update { !it }
    }

    fun toggleGroupCollapsed(groupName: String) {
        collapsedGroups.update { current ->
            if (groupName in current) current - groupName else current + groupName
        }
    }

    fun selectAlbum(album: Album) {
        selectedAlbum.value = album
    }

    fun closeAlbum() {
        selectedAlbum.value = null
    }

    fun onPermissionGranted() {
        _uiState.update { it.copy(hasPermission = true) }
        loadMedia()
    }

    private fun loadMedia() {
        viewModelScope.launch {
            combine(
                getAllMediaUseCase(),
                getAlbumsUseCase(),
                getUserAlbumGroupsUseCase(),
                getAlbumGroupsUseCase()
            ) { media, albums, userGroups, groupOverrides ->
                val sections = buildAlbumGroupSections(albums, groupOverrides)
                val standardGroupTitles = setOf("Smart Albums", "My Albums", "Camera & Device", "Social & Apps", "Other Albums")
                val allCustomGroupNames = (userGroups.map { it.name } + groupOverrides.values)
                    .filter { it.isNotBlank() && it !in standardGroupTitles }
                    .distinct()
                    .sorted()
                val userGroupUiModels = allCustomGroupNames.mapIndexed { index, groupName ->
                    val matchingAlbums = albums.filter { album ->
                        val key = "album_${album.id}"
                        val idKey = "id_${album.id}"
                        groupOverrides[key] == groupName ||
                        groupOverrides[idKey] == groupName ||
                        userGroups.any { it.name.equals(groupName, ignoreCase = true) && (key in it.albumKeys || idKey in it.albumKeys) }
                    }
                    UserAlbumGroupUiModel(
                        id = userGroups.find { it.name.equals(groupName, ignoreCase = true) }?.id ?: (index + 1000L),
                        name = groupName,
                        albums = matchingAlbums
                    )
                }.filter { it.albums.isNotEmpty() }

                val groupedAlbumIds = userGroupUiModels.flatMap { it.albums.map { alb -> alb.id } }.toSet()
                val ungrouped = albums.filter { it.id !in groupedAlbumIds }
                val mediaGroups = media.groupByDate()
                val videos = media.filterIsInstance<MediaItem.Video>()

                GalleryLoadedData(
                    media = media,
                    mediaGroups = mediaGroups,
                    albums = albums,
                    albumGroups = sections,
                    userAlbumGroups = userGroupUiModels,
                    rawUserGroups = userGroups,
                    ungroupedAlbums = ungrouped,
                    userGroupOverrides = groupOverrides,
                    videos = videos
                )
            }
                .flowOn(Dispatchers.Default)
                .catch { e ->
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
                .collect { data ->
                    _uiState.update { current ->
                        current.copy(
                            allMedia = data.media,
                            mediaGroups = data.mediaGroups,
                            albums = data.albums,
                            albumGroups = data.albumGroups,
                            userAlbumGroups = data.userAlbumGroups,
                            rawUserGroups = data.rawUserGroups,
                            ungroupedAlbums = data.ungroupedAlbums,
                            userGroupOverrides = data.userGroupOverrides,
                            videos = data.videos,
                            isLoading = false,
                            hasPermission = true
                        )
                    }
                    // Keep selectedAlbum in sync with updated album items
                    selectedAlbum.value?.let { currentOpen ->
                        val updated = data.albums.find { it.id == currentOpen.id }
                        if (updated != null && updated.itemCount > 0) {
                            selectedAlbum.value = updated
                        } else {
                            closeAlbum()
                        }
                    }
                }
        }
    }

    private fun buildAlbumGroupSections(
        albums: List<Album>,
        overrides: Map<String, String>
    ): List<AlbumGroupSection> {
        val groupedMap = mutableMapOf<String, MutableList<Album>>()

        for (album in albums) {
            val groupTitle = categorizeAlbum(album, overrides)
            groupedMap.getOrPut(groupTitle) { mutableListOf() }.add(album)
        }

        // Ordered sections according to prompt requirements:
        // 1. Smart Albums (Favorites)
        // 2. My Albums (User-created custom albums)
        // [User Custom Assigned Groups (alphabetical)]
        // 3. Camera & Device
        // 4. Social & Apps
        // 5. Other Albums
        val standardPre = listOf("Smart Albums", "My Albums")
        val standardPost = listOf("Camera & Device", "Social & Apps", "Other Albums")

        val userCustomGroups = groupedMap.keys
            .filter { it !in standardPre && it !in standardPost }
            .sorted()

        val orderedKeys = standardPre + userCustomGroups + standardPost

        return orderedKeys.mapNotNull { key ->
            val list = groupedMap[key]
            if (!list.isNullOrEmpty()) {
                AlbumGroupSection(title = key, albums = list)
            } else {
                null
            }
        }
    }

    private fun categorizeAlbum(
        album: Album,
        overrides: Map<String, String>
    ): String {
        val albumKey = "album_${album.id}"
        val userAssigned = overrides[albumKey] ?: overrides["id_${album.id}"] ?: overrides[album.id.toString()]
        if (!userAssigned.isNullOrBlank()) {
            return userAssigned.trim()
        }

        if (album.isSmartAlbum) {
            return "Smart Albums"
        }
        if (album.isCustom) {
            return "My Albums"
        }

        val nameLower = album.name.lowercase(Locale.ROOT).trim()
        val cameraDeviceNames = setOf(
            "camera", "screenshots", "screenshot", "downloads", "download",
            "pictures", "movies", "dcim"
        )
        if (nameLower in cameraDeviceNames) {
            return "Camera & Device"
        }

        val socialAppKeywords = listOf(
            "whatsapp", "instagram", "telegram", "snapchat", "messenger",
            "facebook", "twitter", "reddit", "tiktok", "signal", "wechat", "discord"
        )
        if (socialAppKeywords.any { nameLower.contains(it) }) {
            return "Social & Apps"
        }

        return "Other Albums"
    }

    fun onTabSelected(tab: GalleryTab) {
        selectedTab.value = tab
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearPendingDeleteIntent() {
        _uiState.update { it.copy(pendingDeleteIntentSender = null) }
    }

    // ── Favorites Operations ────────────────────────────────────────────────

    fun toggleFavoritesForSelected() {
        val selected = _uiState.value.selectedItems
        if (selected.isEmpty()) return

        val allFav = selected.all { it.isFavorite }
        val targetFav = !allFav
        viewModelScope.launch {
            toggleFavoriteUseCase.setFavorites(selected.map { it.id }, targetFav)
            _uiState.update { current ->
                current.copy(
                    selectedItemIds = emptySet(),
                    error = if (targetFav) "Added ${selected.size} item(s) to Favorites" else "Removed ${selected.size} item(s) from Favorites"
                )
            }
        }
    }

    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch {
            toggleFavoriteUseCase(item.id)
        }
    }

    // ── Custom Albums Operations ────────────────────────────────────────────

    fun createCustomAlbum(name: String, mediaIds: List<Long> = emptyList(), onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val newId = createCustomAlbumUseCase(name, mediaIds)
            _uiState.update { it.copy(error = "Album '$name' created") }
            onCreated?.invoke(newId)
        }
    }

    fun addSelectedToCustomAlbum(albumId: Long) {
        val selected = _uiState.value.selectedItems
        if (selected.isEmpty()) return
        viewModelScope.launch {
            addItemsToCustomAlbumUseCase(albumId, selected.map { it.id })
            _uiState.update { current ->
                current.copy(
                    selectedItemIds = emptySet(),
                    error = "Added ${selected.size} item(s) to album"
                )
            }
        }
    }

    fun createAndAddSelectedToCustomAlbum(albumName: String) {
        val selected = _uiState.value.selectedItems
        viewModelScope.launch {
            createCustomAlbumUseCase(albumName, selected.map { it.id })
            _uiState.update { current ->
                current.copy(
                    selectedItemIds = emptySet(),
                    error = "Created '$albumName' with ${selected.size} item(s)"
                )
            }
        }
    }

    fun addItemsToCustomAlbum(albumId: Long, items: List<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            addItemsToCustomAlbumUseCase(albumId, items.map { it.id })
            _uiState.update { it.copy(error = "Added ${items.size} item(s)") }
        }
    }

    fun removeSelectedFromCustomAlbum(albumId: Long, items: List<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            removeItemsFromCustomAlbumUseCase(albumId, items.map { it.id })
            _uiState.update { it.copy(error = "Removed ${items.size} item(s) from album") }
        }
    }

    fun renameCustomAlbum(albumId: Long, newName: String) {
        viewModelScope.launch {
            renameCustomAlbumUseCase(albumId, newName)
            _uiState.update { it.copy(error = "Album renamed to '$newName'") }
        }
    }

    fun deleteCustomAlbum(albumId: Long) {
        viewModelScope.launch {
            deleteCustomAlbumUseCase(albumId)
            if (selectedAlbum.value?.id == albumId) {
                selectedAlbum.value = null
            }
            _uiState.update { it.copy(error = "Album deleted") }
        }
    }

    // ── Album Group Operations ──────────────────────────────────────────────

    fun createUserAlbumGroup(name: String, albumKeys: List<String>) {
        viewModelScope.launch {
            createUserAlbumGroupUseCase(name, albumKeys)
            isGroupedView.value = true
            _uiState.update { it.copy(error = "Group '$name' created") }
        }
    }

    fun addAlbumsToGroup(groupId: Long, albumKeys: List<String>) {
        viewModelScope.launch {
            addAlbumsToUserGroupUseCase(groupId, albumKeys)
            isGroupedView.value = true
            _uiState.update { it.copy(error = "Albums added to group") }
        }
    }

    fun removeAlbumsFromGroup(groupId: Long, albumKeys: List<String>) {
        viewModelScope.launch {
            removeAlbumsFromUserGroupUseCase(groupId, albumKeys)
            _uiState.update { it.copy(error = "Albums removed from group") }
        }
    }

    fun updateGroupAlbums(groupId: Long, currentKeys: Set<String>, targetKeys: Set<String>) {
        viewModelScope.launch {
            val toAdd = (targetKeys - currentKeys).toList()
            val toRemove = (currentKeys - targetKeys).toList()
            if (toAdd.isNotEmpty()) addAlbumsToUserGroupUseCase(groupId, toAdd)
            if (toRemove.isNotEmpty()) removeAlbumsFromUserGroupUseCase(groupId, toRemove)
            _uiState.update { it.copy(error = "Group updated") }
        }
    }

    fun renameUserAlbumGroup(groupId: Long, newName: String) {
        viewModelScope.launch {
            renameUserAlbumGroupUseCase(groupId, newName)
            _uiState.update { it.copy(error = "Group renamed to '$newName'") }
        }
    }

    fun deleteUserAlbumGroup(groupId: Long) {
        viewModelScope.launch {
            deleteUserAlbumGroupUseCase(groupId)
            _uiState.update { it.copy(error = "Group dissolved. Albums returned to original groups.") }
        }
    }

    fun setAlbumGroup(album: Album, groupName: String) {
        viewModelScope.launch {
            setAlbumGroupUseCase("album_${album.id}", groupName)
            isGroupedView.value = true
            _uiState.update { it.copy(error = "\"${album.name}\" moved to group '$groupName'") }
        }
    }

    fun resetAlbumGroup(album: Album) {
        viewModelScope.launch {
            setAlbumGroupUseCase.removeGroup("album_${album.id}")
            _uiState.update { it.copy(error = "\"${album.name}\" reset to automatic group") }
        }
    }

    // ── Multi-Selection Operations ──────────────────────────────────────────

    fun startSelection(id: Long) {
        _uiState.update { current ->
            current.copy(selectedItemIds = current.selectedItemIds + id)
        }
    }

    fun toggleSelection(id: Long) {
        _uiState.update { current ->
            val updated = if (id in current.selectedItemIds) {
                current.selectedItemIds - id
            } else {
                current.selectedItemIds + id
            }
            current.copy(selectedItemIds = updated)
        }
    }

    fun selectAll() {
        _uiState.update { current ->
            val visibleItems = when (selectedTab.value) {
                GalleryTab.TIMELINE -> current.allMedia
                GalleryTab.VIDEOS -> current.videos
                GalleryTab.ALBUMS -> emptyList()
            }
            val allIds = visibleItems.map { it.id }.toSet()
            val newSelected = if (current.selectedItemIds.containsAll(allIds)) {
                emptySet()
            } else {
                allIds
            }
            current.copy(selectedItemIds = newSelected)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedItemIds = emptySet()) }
    }

    // ── Album Multi-Selection Operations ────────────────────────────────────

    fun startAlbumSelection(albumId: Long) {
        _uiState.update { current ->
            current.copy(selectedAlbumIds = current.selectedAlbumIds + albumId)
        }
    }

    fun toggleAlbumSelection(albumId: Long) {
        _uiState.update { current ->
            val updated = if (albumId in current.selectedAlbumIds) {
                current.selectedAlbumIds - albumId
            } else {
                current.selectedAlbumIds + albumId
            }
            current.copy(selectedAlbumIds = updated)
        }
    }

    fun selectAllAlbums() {
        _uiState.update { current ->
            val allIds = current.albums.map { it.id }.toSet()
            val newSelected = if (current.selectedAlbumIds.containsAll(allIds)) {
                emptySet()
            } else {
                allIds
            }
            current.copy(selectedAlbumIds = newSelected)
        }
    }

    fun clearAlbumSelection() {
        _uiState.update { it.copy(selectedAlbumIds = emptySet()) }
    }

    fun deleteSelectedAlbums() {
        val albumsToDelete = _uiState.value.selectedAlbums
        if (albumsToDelete.isEmpty()) return

        val allItems = albumsToDelete.flatMap { it.items }.distinctBy { it.id }
        val customAlbumsToDelete = albumsToDelete.filter { it.isCustom }

        viewModelScope.launch {
            customAlbumsToDelete.forEach { album ->
                val dbId = if (Album.isCustomAlbumId(album.id)) Album.fromCustomAlbumDisplayId(album.id) else album.id
                deleteCustomAlbumUseCase(dbId)
            }
        }

        if (allItems.isNotEmpty()) {
            moveItemsToRecycleBin(allItems)
        }

        clearAlbumSelection()
        _uiState.update { it.copy(error = "${albumsToDelete.size} album(s) deleted") }
    }

    fun moveSelectedAlbumsToGroup(groupName: String) {
        val selectedAlbums = _uiState.value.selectedAlbums
        if (selectedAlbums.isEmpty()) return

        val albumKeys = selectedAlbums.map { "album_${it.id}" }
        viewModelScope.launch {
            mediaRepository.setAlbumsGroup(albumKeys, groupName)
            isGroupedView.value = true
            clearAlbumSelection()
            _uiState.update { it.copy(error = "Moved ${selectedAlbums.size} album(s) to group \"$groupName\"") }
        }
    }

    fun ungroupSelectedAlbums() {
        val selectedAlbums = _uiState.value.selectedAlbums
        if (selectedAlbums.isEmpty()) return

        val albumKeys = selectedAlbums.map { "album_${it.id}" }
        viewModelScope.launch {
            mediaRepository.removeAlbumsFromGroup(albumKeys)
            clearAlbumSelection()
            _uiState.update { it.copy(error = "Removed ${selectedAlbums.size} album(s) from groups") }
        }
    }

    fun setAlbumCover(album: Album, coverUri: Uri) {
        viewModelScope.launch {
            mediaRepository.setAlbumCover("album_${album.id}", coverUri.toString())
            _uiState.update { current ->
                val updatedAlbums = current.albums.map { alb ->
                    if (alb.id == album.id) alb.copy(coverUri = coverUri) else alb
                }
                current.copy(
                    albums = updatedAlbums,
                    error = "Cover image updated for \"${album.name}\""
                )
            }
            clearAlbumSelection()
        }
    }

    fun renameAlbum(album: Album, newName: String) {
        viewModelScope.launch {
            val result = mediaRepository.renameAlbum(album, newName)
            if (result.isSuccess) {
                _uiState.update { it.copy(error = "Album renamed to '$newName'") }
                clearAlbumSelection()
            } else {
                val exception = result.exceptionOrNull()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exception is RecoverableSecurityException) {
                    _uiState.update { it.copy(pendingDeleteIntentSender = exception.userAction.actionIntent.intentSender) }
                } else {
                    _uiState.update { it.copy(error = exception?.message ?: "Failed to rename album") }
                }
            }
        }
    }

    fun moveSelectedAlbumsToVault(
        keepOriginalAlbumNames: Boolean,
        customTargetAlbumName: String = "Default"
    ) {
        val albumsToMove = _uiState.value.selectedAlbums
        if (albumsToMove.isEmpty()) return

        viewModelScope.launch {
            val allItemsWithTarget = mutableListOf<Pair<MediaItem, String>>()
            for (album in albumsToMove) {
                val targetAlbum = if (keepOriginalAlbumNames) album.name else customTargetAlbumName.ifBlank { "Default" }
                for (item in album.items) {
                    allItemsWithTarget.add(item to targetAlbum)
                }
            }

            if (allItemsWithTarget.isEmpty()) {
                clearAlbumSelection()
                return@launch
            }

            _uiState.update {
                it.copy(
                    isPerformingBatchAction = true,
                    batchActionMessage = "Moving to Secure Vault (0 / ${allItemsWithTarget.size})..."
                )
            }

            val movedIds = mutableListOf<Long>()
            val total = allItemsWithTarget.size

            withContext(Dispatchers.IO) {
                allItemsWithTarget.forEachIndexed { index, (item, targetVaultAlbum) ->
                    val mimeType = when (item) {
                        is MediaItem.Photo -> item.mimeType
                        is MediaItem.Video -> "video/*"
                    }
                    val result = addToVaultUseCase(
                        sourceUri = item.uri,
                        originalName = item.name,
                        mimeType = mimeType,
                        isVideo = item is MediaItem.Video,
                        albumName = targetVaultAlbum
                    )
                    if (result.isSuccess) {
                        movedIds.add(item.id)
                    }
                    if ((index + 1) % 5 == 0 || index + 1 == total) {
                        _uiState.update {
                            it.copy(batchActionMessage = "Moving to Secure Vault (${index + 1} / $total)...")
                        }
                    }
                }

                for (album in albumsToMove) {
                    if (album.isCustom) {
                        val dbId = if (Album.isCustomAlbumId(album.id)) Album.fromCustomAlbumDisplayId(album.id) else album.id
                        deleteCustomAlbumUseCase(dbId)
                    }
                }
            }

            _uiState.update { current ->
                val remainingMedia = current.allMedia.filter { it.id !in movedIds }
                val remainingAlbums = current.albums.mapNotNull { album ->
                    if (album.id in albumsToMove.map { it.id } && album.isCustom) {
                        null
                    } else {
                        val filtered = album.items.filter { it.id !in movedIds }
                        if (filtered.size != album.items.size) {
                            album.copy(
                                items = filtered,
                                itemCount = filtered.size,
                                coverUri = filtered.resolveFirstCoverUri() ?: Uri.EMPTY
                            )
                        } else album
                    }
                }
                current.copy(
                    allMedia = remainingMedia,
                    mediaGroups = remainingMedia.groupByDate(),
                    albums = remainingAlbums,
                    videos = remainingMedia.filterIsInstance<MediaItem.Video>(),
                    isPerformingBatchAction = false,
                    batchActionMessage = null,
                    selectedAlbumIds = emptySet(),
                    error = "Moved ${movedIds.size} item(s) from ${albumsToMove.size} album(s) to Secure Vault"
                )
            }
        }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun deleteSelectedItems() {
        val selected = _uiState.value.selectedItems
        if (selected.isEmpty()) return
        moveItemsToRecycleBin(selected)
    }

    companion object {
        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val monthYearFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    }

    fun moveItemsToRecycleBin(items: List<MediaItem>) {
        if (items.isEmpty()) return

        val itemIds = items.map { it.id }.toSet()
        val itemsToMove = items.toList()

        // 1. Immediately hide media IDs reactively across the whole app
        MediaRepositoryImpl.hideMediaIds(itemIds)

        // 2. Clear selected item IDs and optimistically filter them out of _uiState
        _uiState.update { current ->
            val remainingMedia = current.allMedia.filter { it.id !in itemIds }
            val remainingAlbums = current.albums.map { album ->
                val filtered = album.items.filter { it.id !in itemIds }
                if (filtered.size != album.items.size) {
                    album.copy(
                        items = filtered,
                        itemCount = filtered.size,
                        coverUri = filtered.resolveFirstCoverUri() ?: Uri.EMPTY
                    )
                } else album
            }
            current.copy(
                allMedia = remainingMedia,
                mediaGroups = remainingMedia.groupByDate(),
                albums = remainingAlbums,
                videos = remainingMedia.filterIsInstance<MediaItem.Video>(),
                selectedItemIds = emptySet(),
                isPerformingBatchAction = false,
                batchActionMessage = null,
                pendingDeleteIntentSender = null,
                error = "${itemsToMove.size} item(s) moved to Recycle Bin"
            )
        }

        // 3. Remove those items from selectedAlbum if an album is currently open
        selectedAlbum.update { currentAlbum ->
            currentAlbum?.let { album ->
                val remainingItems = album.items.filter { it.id !in itemIds }
                album.copy(
                    items = remainingItems,
                    itemCount = remainingItems.size,
                    coverUri = remainingItems.resolveFirstCoverUri() ?: Uri.EMPTY
                )
            }
        }

        // 4. Dispatch the actual moveToRecycleBin work onto application-scoped CoroutineScope
        applicationScope.launch {
            val failedIds = mutableListOf<Long>()
            for (item in itemsToMove) {
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
                    failedIds.add(item.id)
                }
            }

            if (failedIds.isNotEmpty()) {
                MediaRepositoryImpl.unhideMediaIds(failedIds)
                _uiState.update { current ->
                    current.copy(
                        error = "Failed to move ${failedIds.size} item(s) to Recycle Bin"
                    )
                }
            }
        }
    }

    fun moveSelectedToVault(albumName: String = "Default") {
        val selected = _uiState.value.selectedItems
        if (selected.isEmpty()) return
        moveItemsToVault(selected, albumName)
    }

    fun moveItemsToVault(items: List<MediaItem>, albumName: String = "Default") {
        if (items.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingBatchAction = true, batchActionMessage = "Moving to Secure Folder...") }
            val movedIds = mutableListOf<Long>()

            withContext(Dispatchers.IO) {
                for (item in items) {
                    val mimeType = when (item) {
                        is MediaItem.Photo -> item.mimeType
                        is MediaItem.Video -> "video/*"
                    }
                    val result = addToVaultUseCase(
                        sourceUri = item.uri,
                        originalName = item.name,
                        mimeType = mimeType,
                        isVideo = item is MediaItem.Video,
                        albumName = albumName
                    )
                    if (result.isSuccess) {
                        movedIds.add(item.id)
                    }
                }
            }

            // Immediately remove the moved items from all main gallery views without prompting any dialog
            _uiState.update { current ->
                val remainingMedia = current.allMedia.filter { it.id !in movedIds }
                val remainingAlbums = current.albums.map { album ->
                    val filtered = album.items.filter { it.id !in movedIds }
                    if (filtered.size != album.items.size) {
                        album.copy(
                            items = filtered,
                            itemCount = filtered.size,
                            coverUri = filtered.resolveFirstCoverUri() ?: Uri.EMPTY
                        )
                    } else album
                }
                current.copy(
                    allMedia = remainingMedia,
                    mediaGroups = remainingMedia.groupByDate(),
                    albums = remainingAlbums,
                    videos = remainingMedia.filterIsInstance<MediaItem.Video>(),
                    isPerformingBatchAction = false,
                    batchActionMessage = null,
                    selectedItemIds = emptySet(),
                    pendingDeleteIntentSender = null
                )
            }
            selectedAlbum.update { currentAlbum ->
                currentAlbum?.let { album ->
                    val remainingItems = album.items.filter { it.id !in movedIds }
                    album.copy(
                        items = remainingItems,
                        itemCount = remainingItems.size,
                        coverUri = remainingItems.resolveFirstCoverUri() ?: Uri.EMPTY
                    )
                }
            }
        }
    }

    // ── Move To Album Operations ────────────────────────────────────────────

    fun clearPendingMove() {
        _uiState.update {
            it.copy(
                pendingMoveIntentSender = null,
                pendingMoveItems = null,
                pendingTargetAlbum = null,
                pendingNewAlbumName = null,
                pendingSourceAlbumId = null
            )
        }
    }

    fun moveSelectedToAlbum(targetAlbum: Album?, newAlbumName: String?, sourceAlbumId: Long? = null) {
        val selected = _uiState.value.selectedItems
        if (selected.isEmpty()) return
        moveItemsToAlbum(selected, targetAlbum, newAlbumName, sourceAlbumId)
    }

    fun moveItemsToAlbum(
        items: List<MediaItem>,
        targetAlbum: Album?,
        newAlbumName: String?,
        sourceAlbumId: Long? = null
    ) {
        if (items.isEmpty()) return

        val targetDisplayName = targetAlbum?.name ?: newAlbumName?.trim() ?: "Album"

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPerformingBatchAction = true,
                    batchActionMessage = "Moving to $targetDisplayName..."
                )
            }

            val result = moveMediaToAlbumUseCase(items, targetAlbum, newAlbumName, sourceAlbumId)

            when (result) {
                is MoveAlbumResult.Success -> {
                    val movedIdSet = result.movedIds.toSet()
                    _uiState.update { current ->
                        var targetAlbumFound = false
                        val updatedAlbums = current.albums.map { alb ->
                            when {
                                targetAlbum != null && alb.id == targetAlbum.id -> {
                                    targetAlbumFound = true
                                    val existingIds = alb.items.map { it.id }.toSet()
                                    val newItemsToAdd = items.filter { it.id !in existingIds }
                                    val updatedItems = newItemsToAdd + alb.items
                                    alb.copy(
                                        items = updatedItems,
                                        itemCount = updatedItems.size,
                                        coverUri = updatedItems.resolveFirstCoverUri() ?: Uri.EMPTY
                                    )
                                }
                                sourceAlbumId != null && alb.id == sourceAlbumId -> {
                                    val remaining = alb.items.filter { it.id !in movedIdSet }
                                    alb.copy(
                                        items = remaining,
                                        itemCount = remaining.size,
                                        coverUri = remaining.resolveFirstCoverUri() ?: Uri.EMPTY
                                    )
                                }
                                else -> {
                                    val remaining = alb.items.filter { it.id !in movedIdSet }
                                    if (remaining.size != alb.items.size) {
                                        alb.copy(
                                            items = remaining,
                                            itemCount = remaining.size,
                                            coverUri = remaining.resolveFirstCoverUri() ?: Uri.EMPTY
                                        )
                                    } else alb
                                }
                            }
                        }.toMutableList()

                        if (targetAlbum == null && !newAlbumName.isNullOrBlank()) {
                            val newName = newAlbumName.trim()
                            val newAlb = Album(
                                id = System.currentTimeMillis(),
                                name = newName,
                                coverUri = items.resolveFirstCoverUri() ?: Uri.EMPTY,
                                itemCount = items.size,
                                items = items,
                                isCustom = false,
                                isSmartAlbum = false
                            )
                            updatedAlbums.add(newAlb)
                        } else if (targetAlbum != null && !targetAlbumFound) {
                            val combinedItems = items + targetAlbum.items
                            val newAlb = targetAlbum.copy(
                                items = combinedItems,
                                itemCount = combinedItems.size,
                                coverUri = combinedItems.resolveFirstCoverUri() ?: targetAlbum.coverUri
                            )
                            updatedAlbums.add(newAlb)
                        }

                        val updatedSections = buildAlbumGroupSections(updatedAlbums, current.userGroupOverrides)

                        current.copy(
                            albums = updatedAlbums,
                            albumGroups = updatedSections,
                            selectedItemIds = emptySet(),
                            isPerformingBatchAction = false,
                            batchActionMessage = null,
                            pendingMoveIntentSender = null,
                            pendingMoveItems = null,
                            pendingTargetAlbum = null,
                            pendingNewAlbumName = null,
                            pendingSourceAlbumId = null,
                            error = "Moved ${result.movedCount} item(s) to \"${result.targetAlbumName}\""
                        )
                    }

                    // Keep selectedAlbum synchronized immediately
                    selectedAlbum.update { currentOpen ->
                        if (currentOpen != null) {
                            if (targetAlbum != null && currentOpen.id == targetAlbum.id) {
                                val existingIds = currentOpen.items.map { it.id }.toSet()
                                val newItemsToAdd = items.filter { it.id !in existingIds }
                                val updatedItems = newItemsToAdd + currentOpen.items
                                currentOpen.copy(
                                    items = updatedItems,
                                    itemCount = updatedItems.size,
                                    coverUri = updatedItems.resolveFirstCoverUri() ?: Uri.EMPTY
                                )
                            } else {
                                val remaining = currentOpen.items.filter { it.id !in movedIdSet }
                                if (remaining.isEmpty()) {
                                    null
                                } else {
                                    currentOpen.copy(
                                        items = remaining,
                                        itemCount = remaining.size,
                                        coverUri = remaining.resolveFirstCoverUri() ?: Uri.EMPTY
                                    )
                                }
                            }
                        } else {
                            null
                        }
                    }
                }
                is MoveAlbumResult.RequiresPermission -> {
                    _uiState.update { current ->
                        current.copy(
                            isPerformingBatchAction = false,
                            batchActionMessage = null,
                            pendingMoveIntentSender = result.intentSender,
                            pendingMoveItems = result.pendingItems,
                            pendingTargetAlbum = targetAlbum,
                            pendingNewAlbumName = newAlbumName,
                            pendingSourceAlbumId = sourceAlbumId
                        )
                    }
                }
                is MoveAlbumResult.Error -> {
                    _uiState.update { current ->
                        current.copy(
                            isPerformingBatchAction = false,
                            batchActionMessage = null,
                            pendingMoveIntentSender = null,
                            pendingMoveItems = null,
                            pendingTargetAlbum = null,
                            pendingNewAlbumName = null,
                            pendingSourceAlbumId = null,
                            error = result.message
                        )
                    }
                }
            }
        }
    }

    fun retryPendingMove() {
        val items = _uiState.value.pendingMoveItems ?: return
        val targetAlbum = _uiState.value.pendingTargetAlbum
        val newAlbumName = _uiState.value.pendingNewAlbumName
        val sourceAlbumId = _uiState.value.pendingSourceAlbumId

        clearPendingMove()
        moveItemsToAlbum(items, targetAlbum, newAlbumName, sourceAlbumId)
    }

    private fun List<MediaItem>.groupByDate(): List<MediaGroup> {
        val zoneId = ZoneId.systemDefault()
        return groupBy { item ->
            val instant = Instant.ofEpochSecond(item.dateAdded.coerceAtLeast(0L))
            monthYearFormatter.format(instant.atZone(zoneId))
        }.map { (label, items) ->
            MediaGroup(dateLabel = label, items = items)
        }
    }
}
