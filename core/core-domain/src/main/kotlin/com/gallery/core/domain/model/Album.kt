package com.gallery.core.domain.model

import android.net.Uri

data class Album(
    val id: Long,
    val name: String,
    val coverUri: Uri,
    val itemCount: Int,
    val items: List<MediaItem>,
    val isCustom: Boolean = false,
    val isSmartAlbum: Boolean = false
) {
    constructor(
        id: Long,
        name: String,
        items: List<MediaItem>,
        isCustom: Boolean = false,
        isSmartAlbum: Boolean = false
    ) : this(
        id = id,
        name = name,
        coverUri = items.resolveFirstCoverUri() ?: Uri.EMPTY,
        itemCount = items.size,
        items = items,
        isCustom = isCustom,
        isSmartAlbum = isSmartAlbum
    )

    companion object {
        const val ID_ALL_MEDIA = -1L
        const val ID_VIDEOS = -2L
        const val ID_FAVORITES = -3L
        const val CUSTOM_ALBUM_ID_OFFSET = 100L

        fun toCustomAlbumDisplayId(dbId: Long): Long = -(dbId + CUSTOM_ALBUM_ID_OFFSET)
        fun fromCustomAlbumDisplayId(displayId: Long): Long = (-displayId) - CUSTOM_ALBUM_ID_OFFSET
        fun isCustomAlbumId(displayId: Long): Boolean = displayId <= -100L
    }
}

/**
 * Resolves the cover URI using the first picture (Photo) in the album.
 * If no photos exist, falls back to the first media item (e.g. video), or null if empty.
 */
fun List<MediaItem>.resolveFirstCoverUri(): Uri? =
    firstOrNull { it is MediaItem.Photo }?.uri ?: firstOrNull()?.uri

/**
 * Resolves the cover URI using the first picture (Photo) in the album, falling back to [Uri.EMPTY].
 */
fun List<MediaItem>.resolveFirstCoverUriOrDefault(): Uri =
    resolveFirstCoverUri() ?: Uri.EMPTY

/**
 * Resolves the cover URI for this album's media list (defaults to first picture).
 */
fun List<MediaItem>.resolveCoverUri(): Uri? = resolveFirstCoverUri()

/**
 * Resolves the cover URI using the last picture (Photo) in the album.
 * If no photos exist, falls back to the last media item (e.g. video), or null if empty.
 */
fun List<MediaItem>.resolveLastCoverUri(): Uri? =
    lastOrNull { it is MediaItem.Photo }?.uri ?: lastOrNull()?.uri

/**
 * Resolves the cover URI using the last picture (Photo) in the album, falling back to [Uri.EMPTY].
 */
fun List<MediaItem>.resolveLastCoverUriOrDefault(): Uri =
    resolveLastCoverUri() ?: Uri.EMPTY

/**
 * Dynamically resolves the cover URI of this album from its items,
 * falling back to [Album.coverUri] if items is empty.
 */
val Album.resolvedCoverUri: Uri
    get() = items.resolveFirstCoverUri() ?: coverUri

/**
 * Returns a copy of this album with the items updated, keeping itemCount and coverUri synchronized.
 */
fun Album.withUpdatedItems(newItems: List<MediaItem>): Album = copy(
    items = newItems,
    itemCount = newItems.size,
    coverUri = newItems.resolveFirstCoverUri() ?: Uri.EMPTY
)
