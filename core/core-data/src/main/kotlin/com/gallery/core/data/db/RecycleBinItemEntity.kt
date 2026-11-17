package com.gallery.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "recycle_bin_items")
data class RecycleBinItemEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val originalName: String,
    val storedFilePath: String = "",
    val mimeType: String,
    val originalSizeBytes: Long = 0L,
    val deletedAt: Long = System.currentTimeMillis(),
    val isVideo: Boolean = false,
    val originalUri: String = "",
    val originalMediaId: Long = -1L,
    val bucketName: String = "",
    val relativePath: String? = null,
    val dateAdded: Long = 0L,
    val dateTaken: Long? = null,
    val trashedFilePath: String = storedFilePath
)
