package com.gallery.core.data.repository

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.gallery.core.data.db.AlbumCoverDao
import com.gallery.core.data.db.AlbumCoverEntity
import com.gallery.core.data.db.AlbumGroupDao
import com.gallery.core.data.db.AlbumGroupEntity
import com.gallery.core.data.db.CustomAlbumGroupEntity
import com.gallery.core.data.db.CustomAlbumGroupMemberEntity
import com.gallery.core.data.db.CustomAlbumDao
import com.gallery.core.data.db.CustomAlbumEntity
import com.gallery.core.data.db.CustomAlbumItemEntity
import com.gallery.core.data.db.FavoriteDao
import com.gallery.core.data.db.FavoriteItemEntity
import com.gallery.core.data.db.RecycleBinDao
import com.gallery.core.data.db.VaultDao
import com.gallery.core.domain.model.Album
import com.gallery.core.domain.model.MediaItem
import com.gallery.core.domain.model.MoveAlbumResult
import com.gallery.core.domain.model.UserAlbumGroup
import com.gallery.core.domain.model.resolveFirstCoverUri
import com.gallery.core.domain.repository.MediaRepository
import com.gallery.core.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class MediaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultDao: VaultDao,
    private val recycleBinDao: RecycleBinDao,
    private val favoriteDao: FavoriteDao,
    private val customAlbumDao: CustomAlbumDao,
    private val albumGroupDao: AlbumGroupDao,
    private val albumCoverDao: AlbumCoverDao,
    private val settingsRepository: SettingsRepository
) : MediaRepository {

    companion object {
        private val _hiddenMediaIdsFlow = MutableStateFlow<Set<Long>>(emptySet())
        val hiddenMediaIdsFlow: StateFlow<Set<Long>> = _hiddenMediaIdsFlow.asStateFlow()

        fun hideMediaIds(ids: Collection<Long>) {
            if (ids.isEmpty()) return
            _hiddenMediaIdsFlow.update { current -> current + ids }
        }

        fun unhideMediaIds(ids: Collection<Long>) {
            if (ids.isEmpty()) return
            _hiddenMediaIdsFlow.update { current -> current - ids.toSet() }
        }
    }

    override fun hideMediaIds(ids: Collection<Long>) = Companion.hideMediaIds(ids)
    override fun unhideMediaIds(ids: Collection<Long>) = Companion.unhideMediaIds(ids)

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mediaStoreFlow: Flow<List<MediaItem>> = callbackFlow {
        var debounceJob: Job? = null
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                debounceJob?.cancel()
                debounceJob = launch {
                    delay(200L)
                    send(queryAllMedia())
                }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        // Emit immediately
        send(queryAllMedia())
        awaitClose {
            debounceJob?.cancel()
            context.contentResolver.unregisterContentObserver(observer)
        }
    }.flowOn(Dispatchers.IO)

    private val allMediaFlow: Flow<List<MediaItem>> = combine(
        mediaStoreFlow,
        vaultDao.observeAllOriginalNames(),
        recycleBinDao.observeAllDeletedNames(),
        favoriteDao.getAllFavoriteIds(),
        hiddenMediaIdsFlow
    ) { mediaList, vaultedNames, deletedNames, favoriteIds, hiddenIds ->
        val hiddenSet = (vaultedNames + deletedNames).toHashSet()
        val favoriteSet = favoriteIds.toHashSet()
        mediaList.filter { it.name !in hiddenSet && it.id !in hiddenIds }.map { item ->
            val isFav = item.id in favoriteSet
            when (item) {
                is MediaItem.Photo -> if (item.isFavorite != isFav) item.copy(isFavorite = isFav) else item
                is MediaItem.Video -> if (item.isFavorite != isFav) item.copy(isFavorite = isFav) else item
            }
        }
    }
        .flowOn(Dispatchers.IO)
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.WhileSubscribed(5000),
            replay = 1
        )

    override fun getAllMedia(): Flow<List<MediaItem>> = allMediaFlow

    override fun getAlbums(): Flow<List<Album>> = combine(
        getAllMedia(),
        customAlbumDao.getAllAlbums(),
        customAlbumDao.getAllAlbumItems(),
        albumCoverDao.observeAll(),
        settingsRepository.settings
    ) { mediaList, customAlbums, customAlbumItems, coversList, settings ->
        val coverMap = coversList.associate { it.albumKey to it.coverUri }

        fun resolveAlbumCover(albumId: Long, albumName: String, items: List<MediaItem>): Uri {
            val customCoverStr = coverMap["album_$albumId"]
                ?: coverMap["id_$albumId"]
                ?: coverMap["name_${albumName.trim().lowercase()}"]
            if (customCoverStr != null) {
                val customUri = Uri.parse(customCoverStr)
                if (items.any { it.uri == customUri || it.uri.toString() == customCoverStr }) {
                    return customUri
                }
            }
            return items.resolveFirstCoverUri() ?: Uri.EMPTY
        }

        // 1. Smart Album: Favorites
        val favoriteItems = mediaList.filter { it.isFavorite }
        val favoritesAlbum = Album(
            id = Album.ID_FAVORITES,
            name = "Favorites",
            coverUri = resolveAlbumCover(Album.ID_FAVORITES, "Favorites", favoriteItems),
            itemCount = favoriteItems.size,
            items = favoriteItems,
            isCustom = false,
            isSmartAlbum = true
        )

        // 2. Custom User Albums
        val mediaMap = mediaList.associateBy { it.id }
        val customAlbumList = customAlbums.map { customEntity ->
            val itemIds = customAlbumItems
                .filter { it.albumId == customEntity.id }
                .map { it.mediaId }
            val items = itemIds.mapNotNull { mediaMap[it] }
            val displayId = Album.toCustomAlbumDisplayId(customEntity.id)
            Album(
                id = displayId,
                name = customEntity.name,
                coverUri = resolveAlbumCover(displayId, customEntity.name, items),
                itemCount = items.size,
                items = items,
                isCustom = true,
                isSmartAlbum = false
            )
        }

        // 3. MediaStore folder albums
        val mediaStoreAlbums = if (settings.mergeAlbums) {
            mediaList
                .groupBy { it.bucketName.trim().lowercase() }
                .map { (_, items) ->
                    val sortedItems = items.sortedByDescending { it.dateAdded }
                    val primaryItem = sortedItems.first()
                    Album(
                        id = primaryItem.bucketId,
                        name = primaryItem.bucketName,
                        coverUri = resolveAlbumCover(primaryItem.bucketId, primaryItem.bucketName, sortedItems),
                        itemCount = items.size,
                        items = sortedItems,
                        isCustom = false,
                        isSmartAlbum = false
                    )
                }.sortedByDescending { it.itemCount }
        } else {
            mediaList.groupBy { it.bucketId }.map { (bucketId, items) ->
                val sortedItems = items.sortedByDescending { it.dateAdded }
                val primaryItem = sortedItems.first()
                Album(
                    id = bucketId,
                    name = items.first().bucketName,
                    coverUri = resolveAlbumCover(bucketId, items.first().bucketName, sortedItems),
                    itemCount = items.size,
                    items = sortedItems,
                    isCustom = false,
                    isSmartAlbum = false
                )
            }.sortedByDescending { it.itemCount }
        }

        listOf(favoritesAlbum) + customAlbumList + mediaStoreAlbums
    }.flowOn(Dispatchers.IO)

    override fun getVideos(): Flow<List<MediaItem.Video>> = getAllMedia().map { mediaList ->
        mediaList.filterIsInstance<MediaItem.Video>()
    }

    override suspend fun deleteMedia(id: Long, isVideo: Boolean): Boolean {
        return try {
            val contentUri = if (isVideo) {
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            } else {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
            context.contentResolver.delete(contentUri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    // ── Favorites ───────────────────────────────────────────────────────────

    override fun getAllFavoriteIds(): Flow<List<Long>> = favoriteDao.getAllFavoriteIds()

    override fun isFavorite(mediaId: Long): Flow<Boolean> = favoriteDao.isFavorite(mediaId)

    override suspend fun toggleFavorite(mediaId: Long): Boolean = withContext(Dispatchers.IO) {
        val isCurrentlyFav = favoriteDao.isFavoriteSync(mediaId)
        if (isCurrentlyFav) {
            favoriteDao.deleteFavorite(mediaId)
            false
        } else {
            favoriteDao.insertFavorite(FavoriteItemEntity(mediaId = mediaId))
            true
        }
    }

    override suspend fun setFavorites(mediaIds: List<Long>, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        if (isFavorite) {
            favoriteDao.insertFavorites(mediaIds.map { FavoriteItemEntity(it) })
        } else {
            favoriteDao.deleteFavorites(mediaIds)
        }
    }

    // ── Custom Albums ───────────────────────────────────────────────────────

    override suspend fun createCustomAlbum(name: String, mediaIds: List<Long>): Long = withContext(Dispatchers.IO) {
        val albumId = customAlbumDao.insertAlbum(CustomAlbumEntity(name = name))
        if (mediaIds.isNotEmpty()) {
            customAlbumDao.insertAlbumItems(
                mediaIds.map { CustomAlbumItemEntity(albumId = albumId, mediaId = it) }
            )
        }
        albumId
    }

    override suspend fun addItemsToCustomAlbum(albumId: Long, mediaIds: List<Long>) = withContext(Dispatchers.IO) {
        val dbId = if (Album.isCustomAlbumId(albumId)) Album.fromCustomAlbumDisplayId(albumId) else albumId
        customAlbumDao.insertAlbumItems(
            mediaIds.map { CustomAlbumItemEntity(albumId = dbId, mediaId = it) }
        )
    }

    override suspend fun removeItemsFromCustomAlbum(albumId: Long, mediaIds: List<Long>) = withContext(Dispatchers.IO) {
        val dbId = if (Album.isCustomAlbumId(albumId)) Album.fromCustomAlbumDisplayId(albumId) else albumId
        customAlbumDao.deleteAlbumItems(dbId, mediaIds)
    }

    override suspend fun renameCustomAlbum(albumId: Long, newName: String) = withContext(Dispatchers.IO) {
        val dbId = if (Album.isCustomAlbumId(albumId)) Album.fromCustomAlbumDisplayId(albumId) else albumId
        val existing = customAlbumDao.getAlbumById(dbId)
        if (existing != null) {
            customAlbumDao.updateAlbum(existing.copy(name = newName))
        }
    }

    override suspend fun deleteCustomAlbum(albumId: Long) = withContext(Dispatchers.IO) {
        val dbId = if (Album.isCustomAlbumId(albumId)) Album.fromCustomAlbumDisplayId(albumId) else albumId
        customAlbumDao.deleteAlbumAndItems(dbId)
    }

    // ── Album Groups ────────────────────────────────────────────────────────

    override fun getAlbumGroups(): Flow<Map<String, String>> = combine(
        albumGroupDao.getAllAlbumGroups(),
        getUserAlbumGroups()
    ) { legacyList, userGroups ->
        val map = legacyList.associate { it.albumKey to it.groupName }.toMutableMap()
        userGroups.forEach { group ->
            group.albumKeys.forEach { key ->
                map[key] = group.name
            }
        }
        map
    }.flowOn(Dispatchers.IO)

    override suspend fun setAlbumGroup(albumKey: String, groupName: String) = withContext(Dispatchers.IO) {
        setAlbumsGroup(listOf(albumKey), groupName)
    }

    override suspend fun removeAlbumGroup(albumKey: String) = withContext(Dispatchers.IO) {
        albumGroupDao.deleteAlbumGroup(albumKey)
        albumGroupDao.removeAlbumFromAllGroups(albumKey)
    }

    override suspend fun setAlbumsGroup(albumKeys: List<String>, groupName: String) = withContext(Dispatchers.IO) {
        val trimmed = groupName.trim()
        if (trimmed.isEmpty()) return@withContext
        val distinctKeys = albumKeys.distinct()
        if (distinctKeys.isEmpty()) return@withContext

        val existingGroup = albumGroupDao.getGroupByName(trimmed)
        val groupId = existingGroup?.groupId ?: albumGroupDao.insertGroup(CustomAlbumGroupEntity(groupName = trimmed))

        distinctKeys.forEach { key ->
            albumGroupDao.removeAlbumFromAllGroups(key)
        }
        val members = distinctKeys.map { key ->
            CustomAlbumGroupMemberEntity(groupId = groupId, albumKey = key)
        }
        albumGroupDao.insertMembers(members)

        val now = System.currentTimeMillis()
        val entities = distinctKeys.map { key ->
            AlbumGroupEntity(albumKey = key, groupName = trimmed, updatedAt = now)
        }
        albumGroupDao.upsertAll(entities)
    }

    override suspend fun removeAlbumsFromGroup(albumKeys: List<String>) = withContext(Dispatchers.IO) {
        val distinctKeys = albumKeys.distinct()
        albumGroupDao.deleteByAlbumKeys(distinctKeys)
        distinctKeys.forEach { key ->
            albumGroupDao.removeAlbumFromAllGroups(key)
        }
    }

    // ── Album Covers ────────────────────────────────────────────────────────

    override fun getAlbumCovers(): Flow<Map<String, String>> =
        albumCoverDao.observeAll().map { list ->
            list.associate { it.albumKey to it.coverUri }
        }.flowOn(Dispatchers.IO)

    override suspend fun setAlbumCover(albumKey: String, coverUri: String) = withContext(Dispatchers.IO) {
        albumCoverDao.upsert(
            AlbumCoverEntity(albumKey = albumKey, coverUri = coverUri, updatedAt = System.currentTimeMillis())
        )
    }

    override suspend fun removeAlbumCover(albumKey: String) = withContext(Dispatchers.IO) {
        albumCoverDao.delete(albumKey)
    }

    // ── Rename Album ────────────────────────────────────────────────────────

    override suspend fun renameAlbum(album: Album, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == album.name) {
            return@withContext Result.success(Unit)
        }

        // 1. Custom Room album
        if (album.isCustom) {
            renameCustomAlbum(album.id, trimmed)
            return@withContext Result.success(Unit)
        }

        // 2. Smart albums cannot be renamed
        if (album.id <= 0L && !album.isCustom) {
            return@withContext Result.failure(IllegalStateException("Cannot rename smart album"))
        }

        val sanitizedName = trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (sanitizedName.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid album name"))
        }

        if (album.items.isEmpty()) {
            return@withContext Result.success(Unit)
        }

        try {
            var currentFolderDir: File? = null
            var parentDir: File? = null
            for (item in album.items) {
                val path = item.filePath ?: queryDataPath(item.uri)
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    val parent = file.parentFile
                    if (parent != null) {
                        currentFolderDir = parent
                        parentDir = parent.parentFile
                        break
                    }
                }
            }

            var dirRenamed = false
            if (currentFolderDir != null && parentDir != null && currentFolderDir.exists()) {
                val newTargetDir = File(parentDir, sanitizedName)
                if (!newTargetDir.exists() && currentFolderDir.renameTo(newTargetDir)) {
                    dirRenamed = true
                    val newFiles = newTargetDir.listFiles()?.map { it.absolutePath } ?: emptyList()
                    if (newFiles.isNotEmpty()) {
                        scanFilesSync(newFiles, List(newFiles.size) { "" })
                    }
                }
            }

            if (!dirRenamed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val sampleItem = album.items.firstOrNull()
                var baseRelativePrefix: String? = null
                if (sampleItem != null) {
                    try {
                        context.contentResolver.query(
                            sampleItem.uri,
                            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                            null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val col = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                                if (col >= 0) {
                                    val rel = cursor.getString(col)
                                    if (!rel.isNullOrBlank()) {
                                        val segments = rel.trim('/').split('/')
                                        if (segments.size > 1) {
                                            baseRelativePrefix = segments.dropLast(1).joinToString("/", postfix = "/")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                val newRelPath = (baseRelativePrefix ?: "${Environment.DIRECTORY_PICTURES}/") + "$sanitizedName/"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, newRelPath)
                }

                for (item in album.items) {
                    try {
                        context.contentResolver.update(item.uri, values, null, null)
                    } catch (e: RecoverableSecurityException) {
                        return@withContext Result.failure(e)
                    } catch (_: Exception) {}
                }
            }

            context.contentResolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null)
            context.contentResolver.notifyChange(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── User-Selected Album Groups ───────────────────────────────────────────

    override fun getUserAlbumGroups(): Flow<List<UserAlbumGroup>> = combine(
        albumGroupDao.observeAllGroups(),
        albumGroupDao.observeAllMembers()
    ) { groups, members ->
        val memberMap = members.groupBy { it.groupId }
        groups.map { entity ->
            UserAlbumGroup(
                id = entity.groupId,
                name = entity.groupName,
                albumKeys = memberMap[entity.groupId]?.map { it.albumKey } ?: emptyList(),
                createdAt = entity.createdAt
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun createUserAlbumGroup(name: String, albumKeys: List<String>): Long = withContext(Dispatchers.IO) {
        albumGroupDao.createGroupWithAlbums(name.trim(), albumKeys)
    }

    override suspend fun addAlbumsToGroup(groupId: Long, albumKeys: List<String>) = withContext(Dispatchers.IO) {
        albumGroupDao.addAlbumsToGroup(groupId, albumKeys)
    }

    override suspend fun removeAlbumsFromGroup(groupId: Long, albumKeys: List<String>) = withContext(Dispatchers.IO) {
        albumGroupDao.removeAlbumsFromGroup(groupId, albumKeys)
    }

    override suspend fun renameUserAlbumGroup(groupId: Long, newName: String) = withContext(Dispatchers.IO) {
        albumGroupDao.renameGroup(groupId, newName.trim())
    }

    override suspend fun deleteUserAlbumGroup(groupId: Long) = withContext(Dispatchers.IO) {
        albumGroupDao.deleteGroup(groupId)
    }

    // ── Move Media Between Albums ──────────────────────────────────────────

    override suspend fun moveMediaToAlbum(
        items: List<MediaItem>,
        targetAlbum: Album?,
        newAlbumName: String?,
        sourceAlbumId: Long?
    ): MoveAlbumResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) {
            return@withContext MoveAlbumResult.Error("No items selected to move")
        }

        val targetName = when {
            targetAlbum != null -> targetAlbum.name
            !newAlbumName.isNullOrBlank() -> newAlbumName.trim()
            else -> return@withContext MoveAlbumResult.Error("No target album specified")
        }

        // Accurately verify if source is a custom Room DB album
        val sourceCustomDbId: Long? = when {
            sourceAlbumId == null -> null
            Album.isCustomAlbumId(sourceAlbumId) -> {
                val dbId = Album.fromCustomAlbumDisplayId(sourceAlbumId)
                if (customAlbumDao.getAlbumById(dbId) != null) dbId else null
            }
            else -> {
                if (customAlbumDao.getAlbumById(sourceAlbumId) != null) sourceAlbumId else null
            }
        }
        val isSourceCustom = sourceCustomDbId != null

        // 1. Target is a Custom Room Album
        if (targetAlbum?.isCustom == true) {
            val targetCustomDbId = if (Album.isCustomAlbumId(targetAlbum.id)) {
                Album.fromCustomAlbumDisplayId(targetAlbum.id)
            } else {
                targetAlbum.id
            }
            if (sourceCustomDbId != null) {
                customAlbumDao.deleteAlbumItems(sourceCustomDbId, items.map { it.id })
            }
            customAlbumDao.insertAlbumItems(
                items.map { CustomAlbumItemEntity(albumId = targetCustomDbId, mediaId = it.id) }
            )
            context.contentResolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null)
            context.contentResolver.notifyChange(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null)
            return@withContext MoveAlbumResult.Success(items.map { it.id }, targetName)
        }

        // 2. Target is a physical directory (MediaStore folder or newly created folder)
        val sanitizedName = targetName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            .ifEmpty { "New Album" }

        var targetRelativePath: String? = null
        var targetDir: File? = null

        if (targetAlbum != null && !targetAlbum.isCustom) {
            val sampleItem = targetAlbum.items.firstOrNull()
            if (sampleItem != null) {
                // 1. Try querying RELATIVE_PATH directly from MediaStore if API 29+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH)
                        context.contentResolver.query(sampleItem.uri, projection, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val relCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                                if (relCol >= 0) {
                                    val path = cursor.getString(relCol)
                                    if (!path.isNullOrBlank()) {
                                        targetRelativePath = if (path.endsWith("/")) path else "$path/"
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // 2. Derive parent File from samplePath using canonical paths to resolve symlinks
                val samplePath = sampleItem.filePath
                if (!samplePath.isNullOrBlank()) {
                    val file = File(samplePath)
                    val parent = file.parentFile
                    if (parent != null) {
                        targetDir = parent
                        if (targetRelativePath == null) {
                            try {
                                val canonicalParent = parent.canonicalFile
                                val canonicalRoot = Environment.getExternalStorageDirectory().canonicalFile
                                if (canonicalParent.absolutePath.startsWith(canonicalRoot.absolutePath)) {
                                    val rel = canonicalParent.absolutePath.removePrefix(canonicalRoot.absolutePath).trim('/', '\\').replace('\\', '/')
                                    targetRelativePath = if (rel.isNotEmpty()) "$rel/" else ""
                                }
                            } catch (_: Exception) {
                                val extRoot = Environment.getExternalStorageDirectory().absolutePath
                                if (parent.absolutePath.startsWith(extRoot)) {
                                    val rel = parent.absolutePath.removePrefix(extRoot).trim('/', '\\').replace('\\', '/')
                                    targetRelativePath = if (rel.isNotEmpty()) "$rel/" else ""
                                }
                            }
                        }
                    }
                }
            }

            // If targetAlbum had no loaded items, query MediaStore directly by bucketId
            if (targetDir == null || targetRelativePath == null) {
                try {
                    val projection = arrayOf(MediaStore.MediaColumns.DATA).let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            it + MediaStore.MediaColumns.RELATIVE_PATH
                        } else it
                    }
                    val selection = "${MediaStore.MediaColumns.BUCKET_ID} = ?"
                    val selectionArgs = arrayOf(targetAlbum.id.toString())
                    for (collectionUri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
                        context.contentResolver.query(collectionUri, projection, selection, selectionArgs, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetRelativePath == null) {
                                    val relCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                                    if (relCol >= 0) {
                                        val rel = cursor.getString(relCol)
                                        if (!rel.isNullOrBlank()) {
                                            targetRelativePath = if (rel.endsWith("/")) rel else "$rel/"
                                        }
                                    }
                                }
                                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                                if (dataCol >= 0 && targetDir == null) {
                                    val diskPath = cursor.getString(dataCol)
                                    if (!diskPath.isNullOrBlank()) {
                                        val parent = File(diskPath).parentFile
                                        if (parent != null) targetDir = parent
                                    }
                                }
                            }
                        }
                        if (targetDir != null && targetRelativePath != null) break
                    }
                } catch (_: Exception) {}
            }

            val resolvedRelPath = targetRelativePath ?: "${Environment.DIRECTORY_PICTURES}/$sanitizedName/"
            targetRelativePath = resolvedRelPath
            if (targetDir == null) {
                targetDir = File(Environment.getExternalStorageDirectory(), resolvedRelPath)
            }
        } else {
            // New folder creation
            val isAllVideos = items.isNotEmpty() && items.all { it is MediaItem.Video }
            val baseDir = if (isAllVideos) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            targetRelativePath = "$baseDir/$sanitizedName/"
            targetDir = File(Environment.getExternalStoragePublicDirectory(baseDir), sanitizedName)
        }

        val relPath = targetRelativePath
        val finalRelativePath = if (relPath != null) {
            if (relPath.endsWith("/")) relPath else "$relPath/"
        } else null
        val finalTargetDir = targetDir ?: File(Environment.getExternalStorageDirectory(), sanitizedName)

        try {
            if (!finalTargetDir.exists()) {
                finalTargetDir.mkdirs()
            }
        } catch (_: Exception) {}

        val movedItems = mutableListOf<MediaItem>()
        val pendingPermissionItems = mutableListOf<MediaItem>()
        var permissionIntentSender: IntentSender? = null
        val pathsToScan = mutableListOf<String>()
        val mimeTypesToScan = mutableListOf<String>()

        for (item in items) {
            val isVideo = item is MediaItem.Video
            val mimeType = when (item) {
                is MediaItem.Photo -> item.mimeType.ifEmpty { "image/jpeg" }
                is MediaItem.Video -> "video/mp4"
            }
            val originalName = item.name.ifEmpty {
                item.filePath?.let { File(it).name } ?: "media_${item.id}.${if (isVideo) "mp4" else "jpg"}"
            }

            var destFile = finalTargetDir?.let { File(it, originalName) }
            val finalDisplayName = if (destFile != null && destFile.exists() && destFile.canonicalPath != item.filePath) {
                val nameWithoutExt = File(originalName).nameWithoutExtension
                val ext = File(originalName).extension.let { if (it.isNotEmpty()) ".$it" else "" }
                var counter = 1
                var candidate = File(finalTargetDir, "${nameWithoutExt}_$counter$ext")
                while (candidate.exists()) {
                    counter++
                    candidate = File(finalTargetDir, "${nameWithoutExt}_$counter$ext")
                }
                destFile = candidate
                candidate.name
            } else {
                originalName
            }

            var movedSuccessfully = false

            // Strategy 1: Android 10+ (API 29+) in-place MediaStore row update with RELATIVE_PATH
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && finalRelativePath != null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, finalRelativePath)
                    if (finalDisplayName != originalName) {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, finalDisplayName)
                    }
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }

                var rowsUpdated = 0
                try {
                    rowsUpdated = context.contentResolver.update(item.uri, values, null, null)
                } catch (e: RecoverableSecurityException) {
                    if (permissionIntentSender == null) {
                        permissionIntentSender = e.userAction.actionIntent.intentSender
                    }
                } catch (_: SecurityException) {
                    // Handled below via createWriteRequest
                } catch (_: Exception) {}

                if (rowsUpdated > 0) {
                    movedItems.add(item)
                    movedSuccessfully = true

                    // Query updated row to verify DATA and trigger scan
                    try {
                        context.contentResolver.query(
                            item.uri,
                            arrayOf(MediaStore.MediaColumns.DATA),
                            null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                                if (dataCol >= 0) {
                                    val diskPath = cursor.getString(dataCol)
                                    if (!diskPath.isNullOrBlank()) {
                                        pathsToScan.add(diskPath)
                                        mimeTypesToScan.add(mimeType)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                } else {
                    val hasAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
                    if (hasAllFilesAccess) {
                        if (performDirectFileMove(item, isVideo, mimeType, finalTargetDir, finalDisplayName, pathsToScan, mimeTypesToScan)) {
                            movedItems.add(item)
                            movedSuccessfully = true
                        } else {
                            pendingPermissionItems.add(item)
                        }
                    } else {
                        pendingPermissionItems.add(item)
                    }
                }
            } else {
                // Strategy 2: Legacy Android (< Q) or direct filesystem move
                if (performDirectFileMove(item, isVideo, mimeType, finalTargetDir, finalDisplayName, pathsToScan, mimeTypesToScan)) {
                    movedItems.add(item)
                    movedSuccessfully = true
                } else {
                    pendingPermissionItems.add(item)
                }
            }
        }

        if (pendingPermissionItems.isNotEmpty() && movedItems.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val pendingUris = pendingPermissionItems.map { it.uri }
                    val writeSender = MediaStore.createWriteRequest(context.contentResolver, pendingUris).intentSender
                    return@withContext MoveAlbumResult.RequiresPermission(
                        intentSender = writeSender,
                        pendingItems = pendingPermissionItems
                    )
                } catch (_: Exception) {}
            } else if (permissionIntentSender != null) {
                return@withContext MoveAlbumResult.RequiresPermission(
                    intentSender = permissionIntentSender,
                    pendingItems = pendingPermissionItems
                )
            }
            return@withContext MoveAlbumResult.Error("Unable to move items. Storage permission required.")
        }

        // Synchronously trigger media scan for relocated files and await completion
        if (pathsToScan.isNotEmpty()) {
            scanFilesSync(pathsToScan, mimeTypesToScan)
        }

        val movedIds = movedItems.map { it.id }

        // If source was a custom album, remove moved items from custom album
        if (sourceCustomDbId != null && movedIds.isNotEmpty()) {
            customAlbumDao.deleteAlbumItems(sourceCustomDbId, movedIds)
        }

        context.contentResolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null)
        context.contentResolver.notifyChange(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null)

        if (movedItems.isEmpty() && pendingPermissionItems.isNotEmpty()) {
            return@withContext MoveAlbumResult.Error("Unable to move items. Storage permission required.")
        }

        if (movedItems.isEmpty()) {
            return@withContext MoveAlbumResult.Error("Failed to move items. Please check storage access.")
        }

        return@withContext MoveAlbumResult.Success(movedIds, targetName)
    }

    private suspend fun performDirectFileMove(
        item: MediaItem,
        isVideo: Boolean,
        mimeType: String,
        targetDir: File,
        destFileName: String,
        pathsToScan: MutableList<String>,
        mimeTypesToScan: MutableList<String>
    ): Boolean {
        val sourcePath = item.filePath ?: queryDataPath(item.uri) ?: return false
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return false

        val destFile = File(targetDir, destFileName)
        if (destFile.canonicalPath == sourceFile.canonicalPath) {
            return true
        }

        val oldSourcePath = sourceFile.absolutePath
        var fileMoved = false
        try {
            if (sourceFile.renameTo(destFile)) {
                fileMoved = true
            } else if (copyAndDelete(sourceFile, destFile)) {
                fileMoved = true
            }
        } catch (_: Exception) {
            fileMoved = false
        }

        if (fileMoved && destFile.exists() && destFile.length() > 0L) {
            val collectionUri = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            try {
                context.contentResolver.delete(collectionUri, "${MediaStore.MediaColumns.DATA} = ?", arrayOf(oldSourcePath))
            } catch (_: Exception) {}
            try {
                context.contentResolver.delete(item.uri, null, null)
            } catch (_: Exception) {}

            pathsToScan.add(oldSourcePath)
            pathsToScan.add(destFile.absolutePath)
            mimeTypesToScan.add(mimeType)
            mimeTypesToScan.add(mimeType)
            return true
        }
        return false
    }

    private fun queryDataPath(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (col >= 0) cursor.getString(col) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun scanFilesSync(paths: List<String>, mimeTypes: List<String>) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        suspendCancellableCoroutine<Unit> { continuation ->
            var remaining = paths.size
            MediaScannerConnection.scanFile(
                context,
                paths.toTypedArray(),
                mimeTypes.toTypedArray()
            ) { _, _ ->
                if (--remaining <= 0) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    private fun copyAndDelete(source: File, destination: File): Boolean {
        return try {
            val sourceLength = source.length()
            source.inputStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (destination.exists() && destination.length() == sourceLength && sourceLength > 0L) {
                source.delete()
                true
            } else {
                if (destination.exists()) destination.delete()
                false
            }
        } catch (_: Exception) {
            if (destination.exists()) destination.delete()
            false
        }
    }

    // ── Media Queries ───────────────────────────────────────────────────────

    private fun queryAllMedia(): List<MediaItem> {
        val photos = queryImages()
        val videos = queryVideos()
        return (photos + videos).sortedByDescending { it.dateAdded }
    }

    private fun queryImages(): List<MediaItem.Photo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATA
        )

        return buildList {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    val filePath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    add(
                        MediaItem.Photo(
                            id = id,
                            uri = contentUri,
                            name = cursor.getString(nameCol) ?: "",
                            dateAdded = cursor.getLong(dateCol),
                            bucketName = cursor.getString(bucketNameCol) ?: "Unknown",
                            bucketId = cursor.getLong(bucketIdCol),
                            size = cursor.getLong(sizeCol),
                            width = cursor.getInt(widthCol),
                            height = cursor.getInt(heightCol),
                            mimeType = cursor.getString(mimeCol) ?: "image/jpeg",
                            filePath = filePath
                        )
                    )
                }
            }
        }
    }

    private fun queryVideos(): List<MediaItem.Video> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RESOLUTION,
            MediaStore.Video.Media.DATA
        )

        return buildList {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val resCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RESOLUTION)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                    )
                    val filePath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    add(
                        MediaItem.Video(
                            id = id,
                            uri = contentUri,
                            name = cursor.getString(nameCol) ?: "",
                            dateAdded = cursor.getLong(dateCol),
                            bucketName = cursor.getString(bucketNameCol) ?: "Unknown",
                            bucketId = cursor.getLong(bucketIdCol),
                            size = cursor.getLong(sizeCol),
                            duration = cursor.getLong(durationCol),
                            resolution = cursor.getString(resCol) ?: "",
                            filePath = filePath
                        )
                    )
                }
            }
        }
    }
}
