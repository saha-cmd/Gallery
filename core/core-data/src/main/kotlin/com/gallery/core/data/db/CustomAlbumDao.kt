package com.gallery.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomAlbumDao {
    @Query("SELECT * FROM custom_albums ORDER BY createdAt DESC")
    fun getAllAlbums(): Flow<List<CustomAlbumEntity>>

    @Query("SELECT * FROM custom_albums WHERE id = :id")
    suspend fun getAlbumById(id: Long): CustomAlbumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbum(album: CustomAlbumEntity): Long

    @Update
    suspend fun updateAlbum(album: CustomAlbumEntity)

    @Query("DELETE FROM custom_albums WHERE id = :id")
    suspend fun deleteAlbum(id: Long)

    @Query("DELETE FROM custom_album_items WHERE albumId = :albumId")
    suspend fun deleteAlbumItemsByAlbumId(albumId: Long)

    @Transaction
    suspend fun deleteAlbumAndItems(albumId: Long) {
        deleteAlbumItemsByAlbumId(albumId)
        deleteAlbum(albumId)
    }

    @Query("SELECT * FROM custom_album_items WHERE albumId = :albumId ORDER BY addedAt DESC")
    fun getItemsForAlbum(albumId: Long): Flow<List<CustomAlbumItemEntity>>

    @Query("SELECT * FROM custom_album_items ORDER BY addedAt DESC")
    fun getAllAlbumItems(): Flow<List<CustomAlbumItemEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumItems(items: List<CustomAlbumItemEntity>)

    @Query("DELETE FROM custom_album_items WHERE albumId = :albumId AND mediaId IN (:mediaIds)")
    suspend fun deleteAlbumItems(albumId: Long, mediaIds: List<Long>)
}
