package com.gallery.core.domain.usecase

import android.net.Uri
import com.gallery.core.domain.model.VaultItem
import com.gallery.core.domain.repository.VaultRepository
import javax.inject.Inject

class AddToVaultUseCase @Inject constructor(
    private val repository: VaultRepository
) {
    suspend operator fun invoke(
        sourceUri: Uri,
        originalName: String,
        mimeType: String,
        isVideo: Boolean,
        albumName: String = "Default"
    ): Result<VaultItem> = repository.addToVault(sourceUri, originalName, mimeType, isVideo, albumName)
}
