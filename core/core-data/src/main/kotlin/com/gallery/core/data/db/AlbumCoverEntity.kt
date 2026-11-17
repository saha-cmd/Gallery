package com.gallery.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "album_covers")
data class AlbumCoverEntity(
    @PrimaryKey
    val albumKey: String,
    val coverUri: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface AlbumCoverDao {
    @Query("SELECT * FROM album_covers")
    fun observeAll(): Flow<List<AlbumCoverEntity>>

    @Query("SELECT * FROM album_covers")
    suspend fun getAll(): List<AlbumCoverEntity>

    @Query("SELECT coverUri FROM album_covers WHERE albumKey = :albumKey")
    suspend fun getCover(albumKey: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AlbumCoverEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AlbumCoverEntity>)

    @Query("DELETE FROM album_covers WHERE albumKey = :albumKey")
    suspend fun delete(albumKey: String)

    @Query("DELETE FROM album_covers WHERE albumKey IN (:albumKeys)")
    suspend fun deleteByAlbumKeys(albumKeys: List<String>)
}
