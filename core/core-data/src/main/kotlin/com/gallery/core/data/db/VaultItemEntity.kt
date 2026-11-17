package com.gallery.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "vault_items")
data class VaultItemEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val originalName: String,
    val encryptedFilePath: String,
    val iv: ByteArray,
    val mimeType: String,
    val originalSizeBytes: Long,
    val addedAt: Long = System.currentTimeMillis(),
    val isVideo: Boolean = false,
    val albumName: String = "Default"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultItemEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
