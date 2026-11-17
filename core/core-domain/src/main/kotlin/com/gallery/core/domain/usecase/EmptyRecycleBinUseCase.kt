package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.RecycleBinRepository
import javax.inject.Inject

class EmptyRecycleBinUseCase @Inject constructor(
    private val repository: RecycleBinRepository
) {
    suspend operator fun invoke(): Result<Unit> = repository.emptyRecycleBin()
}
