package com.gallery.core.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RecycleBinDao {
    @Query("SELECT * FROM recycle_bin_items ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<RecycleBinItemEntity>>

    @Query("SELECT originalName FROM recycle_bin_items")
    fun observeAllDeletedNames(): Flow<List<String>>

    @Query("SELECT * FROM recycle_bin_items")
    suspend fun getAll(): List<RecycleBinItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecycleBinItemEntity)

    @Delete
    suspend fun delete(entity: RecycleBinItemEntity)

    @Query("DELETE FROM recycle_bin_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM recycle_bin_items")
    suspend fun deleteAll()

    @Query("SELECT * FROM recycle_bin_items WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): RecycleBinItemEntity?
}
