package com.gallery.core.domain.usecase

import com.gallery.core.domain.model.RecycleBinItem
import com.gallery.core.domain.repository.RecycleBinRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetRecycleBinItemsUseCase @Inject constructor(
    private val repository: RecycleBinRepository
) {
    operator fun invoke(): Flow<List<RecycleBinItem>> = repository.getRecycleBinItems()
}
