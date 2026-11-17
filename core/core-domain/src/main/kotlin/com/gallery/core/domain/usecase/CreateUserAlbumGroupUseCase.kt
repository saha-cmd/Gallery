package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.MediaRepository
import javax.inject.Inject

class CreateUserAlbumGroupUseCase @Inject constructor(
    private val repository: MediaRepository
) {
    suspend operator fun invoke(name: String, albumKeys: List<String>): Long =
        repository.createUserAlbumGroup(name, albumKeys)
}
