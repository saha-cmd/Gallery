package com.gallery.core.domain.usecase

import com.gallery.core.domain.model.UserAlbumGroup
import com.gallery.core.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetUserAlbumGroupsUseCase @Inject constructor(
    private val repository: MediaRepository
) {
    operator fun invoke(): Flow<List<UserAlbumGroup>> = repository.getUserAlbumGroups()
}
