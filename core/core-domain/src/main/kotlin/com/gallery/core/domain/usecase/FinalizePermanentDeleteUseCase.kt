package com.gallery.core.domain.usecase

import com.gallery.core.domain.model.RecycleBinItem
import com.gallery.core.domain.repository.RecycleBinRepository
import javax.inject.Inject

class FinalizePermanentDeleteUseCase @Inject constructor(
    private val repository: RecycleBinRepository
) {
    suspend operator fun invoke(items: List<RecycleBinItem>): Result<Unit> =
        repository.finalizePermanentDelete(items)
}
