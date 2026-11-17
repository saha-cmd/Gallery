package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetAlbumGroupsUseCase @Inject constructor(
    private val repository: MediaRepository
) {
    operator fun invoke(): Flow<Map<String, String>> = repository.getAlbumGroups()
}
