package com.gallery.core.domain.model

data class RecycleBinItem(
    val id: String,
    val originalName: String,
    val storedFilePath: String,
    val mimeType: String,
    val originalSizeBytes: Long,
    val deletedAt: Long,
    val isVideo: Boolean,
    val originalUri: String,
    val originalMediaId: Long = -1L,
    val bucketName: String = "",
    val relativePath: String? = null,
    val dateAdded: Long = 0L,
    val dateTaken: Long? = null,
    val trashedFilePath: String = storedFilePath
) {
    val mediaId: Long get() = originalMediaId
}
