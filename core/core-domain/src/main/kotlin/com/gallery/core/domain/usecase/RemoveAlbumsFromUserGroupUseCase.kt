package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.MediaRepository
import javax.inject.Inject

class RemoveAlbumsFromUserGroupUseCase @Inject constructor(
    private val repository: MediaRepository
) {
    suspend operator fun invoke(groupId: Long, albumKeys: List<String>) =
        repository.removeAlbumsFromGroup(groupId, albumKeys)
}
