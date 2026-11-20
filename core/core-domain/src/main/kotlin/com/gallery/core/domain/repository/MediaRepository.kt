package com.gallery.core.domain.repository

import com.gallery.core.domain.model.Album
import com.gallery.core.domain.model.MediaItem
import com.gallery.core.domain.model.MoveAlbumResult
import com.gallery.core.domain.model.RenameMediaResult
import com.gallery.core.domain.model.UserAlbumGroup
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

interface MediaRepository {
    fun getAllMedia(): Flow<List<MediaItem>>
    fun getAlbums(): Flow<List<Album>>
    fun getVideos(): Flow<List<MediaItem.Video>>
    suspend fun deleteMedia(id: Long, isVideo: Boolean): Boolean

    // Reactive Instant-Hide
    fun hideMediaIds(ids: Collection<Long>)
    fun unhideMediaIds(ids: Collection<Long>)

    // Favorites
    fun getAllFavoriteIds(): Flow<List<Long>>
    fun isFavorite(mediaId: Long): Flow<Boolean>
    suspend fun toggleFavorite(mediaId: Long): Boolean
    suspend fun setFavorites(mediaIds: List<Long>, isFavorite: Boolean)

    // Custom Albums
    suspend fun createCustomAlbum(name: String, mediaIds: List<Long> = emptyList()): Long
    suspend fun addItemsToCustomAlbum(albumId: Long, mediaIds: List<Long>)
    suspend fun removeItemsFromCustomAlbum(albumId: Long, mediaIds: List<Long>)
    suspend fun renameCustomAlbum(albumId: Long, newName: String)
    suspend fun deleteCustomAlbum(albumId: Long)

    // Album Groups
    fun getAlbumGroups(): Flow<Map<String, String>>
    suspend fun setAlbumGroup(albumKey: String, groupName: String)
    suspend fun removeAlbumGroup(albumKey: String)
    suspend fun setAlbumsGroup(albumKeys: List<String>, groupName: String)
    suspend fun removeAlbumsFromGroup(albumKeys: List<String>)

    // Album Covers
    fun getAlbumCovers(): Flow<Map<String, String>>
    suspend fun setAlbumCover(albumKey: String, coverUri: String)
    suspend fun removeAlbumCover(albumKey: String)

    // Album Rename
    suspend fun renameAlbum(album: Album, newName: String): Result<Unit>

    // User-Selected Album Groups
    fun getUserAlbumGroups(): Flow<List<UserAlbumGroup>>
    suspend fun createUserAlbumGroup(name: String, albumKeys: List<String>): Long
    suspend fun addAlbumsToGroup(groupId: Long, albumKeys: List<String>)
    suspend fun removeAlbumsFromGroup(groupId: Long, albumKeys: List<String>)
    suspend fun renameUserAlbumGroup(groupId: Long, newName: String)
    suspend fun deleteUserAlbumGroup(groupId: Long)

    // Move Media Between Albums
    suspend fun moveMediaToAlbum(
        items: List<MediaItem>,
        targetAlbum: Album?,
        newAlbumName: String?,
        sourceAlbumId: Long? = null
    ): MoveAlbumResult

    // Duplicate Analyzer & Rename Media
    suspend fun renameMediaItem(item: MediaItem, newName: String): RenameMediaResult
    suspend fun openInputStream(item: MediaItem): InputStream?
}
