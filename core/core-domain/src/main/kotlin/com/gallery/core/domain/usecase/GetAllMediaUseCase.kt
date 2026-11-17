package com.gallery.core.domain.usecase

import com.gallery.core.domain.model.MediaItem
import com.gallery.core.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetAllMediaUseCase @Inject constructor(
    private val repository: MediaRepository
) {
    operator fun invoke(): Flow<List<MediaItem>> = repository.getAllMedia()
}
