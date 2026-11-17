package com.gallery.core.domain.model

import android.net.Uri

sealed class MediaItem {
    abstract val id: Long
    abstract val uri: Uri
    abstract val name: String
    abstract val dateAdded: Long   // seconds since epoch
    abstract val bucketName: String
    abstract val bucketId: Long
    abstract val size: Long
    abstract val filePath: String?
    abstract val isFavorite: Boolean

    data class Photo(
        override val id: Long,
        override val uri: Uri,
        override val name: String,
        override val dateAdded: Long,
        override val bucketName: String,
        override val bucketId: Long,
        override val size: Long,
        val width: Int,
        val height: Int,
        val mimeType: String,
        override val filePath: String? = null,
        override val isFavorite: Boolean = false
    ) : MediaItem()

    data class Video(
        override val id: Long,
        override val uri: Uri,
        override val name: String,
        override val dateAdded: Long,
        override val bucketName: String,
        override val bucketId: Long,
        override val size: Long,
        val duration: Long,       // milliseconds
        val resolution: String,
        override val filePath: String? = null,
        override val isFavorite: Boolean = false
    ) : MediaItem()
}
