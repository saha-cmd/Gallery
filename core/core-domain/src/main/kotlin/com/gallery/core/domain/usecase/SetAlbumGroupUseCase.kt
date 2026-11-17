package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.MediaRepository
import javax.inject.Inject

class SetAlbumGroupUseCase @Inject constructor(
    private val repository: MediaRepository
) {
    suspend operator fun invoke(albumKey: String, groupName: String) =
        repository.setAlbumGroup(albumKey, groupName)

    suspend fun removeGroup(albumKey: String) =
        repository.removeAlbumGroup(albumKey)
}
