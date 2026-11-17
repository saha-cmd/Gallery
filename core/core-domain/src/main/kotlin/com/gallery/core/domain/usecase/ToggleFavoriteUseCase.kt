package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ToggleFavoriteUseCase @Inject constructor(
    private val repository: MediaRepository
) {
    suspend operator fun invoke(mediaId: Long): Boolean = repository.toggleFavorite(mediaId)

    suspend fun setFavorites(mediaIds: List<Long>, isFavorite: Boolean) =
        repository.setFavorites(mediaIds, isFavorite)

    fun isFavorite(mediaId: Long): Flow<Boolean> = repository.isFavorite(mediaId)

    fun getAllFavoriteIds(): Flow<List<Long>> = repository.getAllFavoriteIds()
}
