package com.gallery.core.domain.usecase

import com.gallery.core.domain.model.VaultItem
import com.gallery.core.domain.repository.VaultRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetVaultItemsUseCase @Inject constructor(
    private val repository: VaultRepository
) {
    operator fun invoke(): Flow<List<VaultItem>> = repository.getVaultItems()
}
