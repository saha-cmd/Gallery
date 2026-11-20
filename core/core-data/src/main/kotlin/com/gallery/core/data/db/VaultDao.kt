package com.gallery.core.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_items ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_items")
    suspend fun getAllEntities(): List<VaultItemEntity>

    @Query("SELECT originalName FROM vault_items")
    fun observeAllOriginalNames(): Flow<List<String>>

    @Query("SELECT originalName FROM vault_items")
    suspend fun getAllOriginalNames(): List<String>

    @Query("SELECT DISTINCT albumName FROM vault_items WHERE albumName IS NOT NULL AND albumName != ''")
    fun observeAllAlbumNames(): Flow<List<String>>

    @Query("SELECT DISTINCT albumName FROM vault_items WHERE albumName IS NOT NULL AND albumName != ''")
    suspend fun getAllAlbumNames(): List<String>

    @Query("SELECT * FROM vault_items WHERE albumName = :albumName ORDER BY addedAt DESC")
    fun observeByAlbum(albumName: String): Flow<List<VaultItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: VaultItemEntity)

    @Delete
    suspend fun delete(entity: VaultItemEntity)

    @Query("DELETE FROM vault_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM vault_items WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): VaultItemEntity?

    @Query("UPDATE vault_items SET originalName = :newName WHERE id = :id")
    suspend fun updateOriginalName(id: String, newName: String)

    @Query("SELECT * FROM vault_items WHERE albumName = :albumName")
    suspend fun getEntitiesByAlbum(albumName: String): List<VaultItemEntity>
}
