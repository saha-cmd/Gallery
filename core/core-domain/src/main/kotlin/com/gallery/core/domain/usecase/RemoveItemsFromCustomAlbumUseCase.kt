package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.MediaRepository
import javax.inject.Inject

class RemoveItemsFromCustomAlbumUseCase @Inject constructor(
    private val repository: MediaRepository
) {
    suspend operator fun invoke(albumId: Long, mediaIds: List<Long>) =
        repository.removeItemsFromCustomAlbum(albumId, mediaIds)
}
