package com.gallery.core.domain.model

data class GallerySettings(
    val selectEssentialAlbums: Boolean = false,
    val essentialAlbumNames: Set<String> = emptySet(),
    val mergeAlbums: Boolean = false,
    val lastUpdateCheckMillis: Long = 0L,
    val skippedVersionCode: Long = 0L
)
