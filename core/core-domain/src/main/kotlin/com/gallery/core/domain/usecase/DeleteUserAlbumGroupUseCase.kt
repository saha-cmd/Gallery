package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.MediaRepository
import javax.inject.Inject

class DeleteUserAlbumGroupUseCase @Inject constructor(
    private val repository: MediaRepository
) {
    suspend operator fun invoke(groupId: Long) =
        repository.deleteUserAlbumGroup(groupId)
}
