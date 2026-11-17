package com.gallery.core.domain.usecase

import android.net.Uri
import com.gallery.core.domain.model.VaultItem
import com.gallery.core.domain.repository.VaultRepository
import javax.inject.Inject

class RestoreFromVaultUseCase @Inject constructor(
    private val repository: VaultRepository
) {
    suspend operator fun invoke(vaultItem: VaultItem): Result<Uri> =
        repository.restoreToGallery(vaultItem)
}
