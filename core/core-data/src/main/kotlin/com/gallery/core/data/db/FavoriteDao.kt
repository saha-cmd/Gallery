package com.gallery.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT mediaId FROM favorite_items ORDER BY addedAt DESC")
    fun getAllFavoriteIds(): Flow<List<Long>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_items WHERE mediaId = :mediaId)")
    fun isFavorite(mediaId: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_items WHERE mediaId = :mediaId)")
    suspend fun isFavoriteSync(mediaId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(entity: FavoriteItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorites(entities: List<FavoriteItemEntity>)

    @Query("DELETE FROM favorite_items WHERE mediaId = :mediaId")
    suspend fun deleteFavorite(mediaId: Long)

    @Query("DELETE FROM favorite_items WHERE mediaId IN (:mediaIds)")
    suspend fun deleteFavorites(mediaIds: List<Long>)
}
