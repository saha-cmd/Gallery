package com.gallery.core.domain.usecase

import com.gallery.core.domain.model.Album
import com.gallery.core.domain.model.MediaItem
import com.gallery.core.domain.model.MoveAlbumResult
import com.gallery.core.domain.repository.MediaRepository
import javax.inject.Inject

class MoveMediaToAlbumUseCase @Inject constructor(
    private val repository: MediaRepository
) {
    suspend operator fun invoke(
        items: List<MediaItem>,
        targetAlbum: Album?,
        newAlbumName: String?,
        sourceAlbumId: Long? = null
    ): MoveAlbumResult = repository.moveMediaToAlbum(items, targetAlbum, newAlbumName, sourceAlbumId)
}
