package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.VaultRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetVaultAlbumNamesUseCase @Inject constructor(
    private val repository: VaultRepository
) {
    operator fun invoke(): Flow<List<String>> = repository.getVaultAlbumNames()
    suspend fun getExisting(): List<String> = repository.getExistingAlbumNames()
}
