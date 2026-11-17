package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.MediaRepository
import javax.inject.Inject

class CreateCustomAlbumUseCase @Inject constructor(
    private val repository: MediaRepository
) {
    suspend operator fun invoke(name: String, mediaIds: List<Long> = emptyList()): Long =
        repository.createCustomAlbum(name, mediaIds)
}
