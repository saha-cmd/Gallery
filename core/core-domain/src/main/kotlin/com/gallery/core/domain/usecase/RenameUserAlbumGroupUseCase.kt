package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.MediaRepository
import javax.inject.Inject

class RenameUserAlbumGroupUseCase @Inject constructor(
    private val repository: MediaRepository
) {
    suspend operator fun invoke(groupId: Long, newName: String) =
        repository.renameUserAlbumGroup(groupId, newName)
}
