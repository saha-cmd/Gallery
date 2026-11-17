package com.gallery.core.domain.model

data class VaultItem(
    val id: String,
    val originalName: String,
    val encryptedFilePath: String,
    val iv: ByteArray,
    val mimeType: String,
    val originalSizeBytes: Long,
    val addedAt: Long,
    val isVideo: Boolean,
    val albumName: String = "Default"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultItem) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
