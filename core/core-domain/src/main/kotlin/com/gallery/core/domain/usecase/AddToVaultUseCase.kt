package com.gallery.core.domain.usecase

import android.net.Uri
import com.gallery.core.domain.model.PendingVaultItem
import com.gallery.core.domain.model.StagedVaultItem
import com.gallery.core.domain.model.VaultItem
import com.gallery.core.domain.model.VaultMoveRequest
import com.gallery.core.domain.model.VaultMoveResult
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
        albumName: String = "Default",
        mediaId: Long = 0L
    ): VaultMoveResult = repository.addToVault(sourceUri, originalName, mimeType, isVideo, albumName, mediaId)

    suspend fun moveMultiple(requests: List<VaultMoveRequest>): VaultMoveResult =
        repository.moveMultipleToVault(requests)

    suspend fun finalizeMove(pendingItems: List<StagedVaultItem>): Result<List<VaultItem>> =
        repository.finalizeMoveToVault(pendingItems)

    suspend fun rollbackMove(pendingItems: List<StagedVaultItem>) =
        repository.rollbackMoveToVault(pendingItems)

    suspend fun commitStagedVaultItems(stagedItems: List<StagedVaultItem>): Result<List<VaultItem>> =
        repository.commitStagedVaultItems(stagedItems)

    suspend fun rollbackStagedVaultItems(stagedItems: List<StagedVaultItem>) =
        repository.rollbackStagedVaultItems(stagedItems)
}
