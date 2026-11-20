package com.gallery.core.domain.repository

import android.content.IntentSender
import android.net.Uri
import com.gallery.core.domain.model.PendingVaultItem
import com.gallery.core.domain.model.StagedVaultItem
import com.gallery.core.domain.model.VaultItem
import com.gallery.core.domain.model.VaultMoveRequest
import com.gallery.core.domain.model.VaultMoveResult
import kotlinx.coroutines.flow.Flow
import java.io.File

interface VaultRepository {
    fun getVaultItems(): Flow<List<VaultItem>>
    fun getVaultAlbumNames(): Flow<List<String>>
    suspend fun getExistingAlbumNames(): List<String>
    suspend fun addToVault(
        sourceUri: Uri,
        originalName: String,
        mimeType: String,
        isVideo: Boolean,
        albumName: String = "Default",
        mediaId: Long = 0L
    ): VaultMoveResult
    suspend fun moveMultipleToVault(requests: List<VaultMoveRequest>): VaultMoveResult
    suspend fun finalizeMoveToVault(pendingItems: List<StagedVaultItem>): Result<List<VaultItem>>
    suspend fun rollbackMoveToVault(pendingItems: List<StagedVaultItem>)
    suspend fun commitStagedVaultItems(stagedItems: List<StagedVaultItem>): Result<List<VaultItem>> = finalizeMoveToVault(stagedItems)
    suspend fun rollbackStagedVaultItems(stagedItems: List<StagedVaultItem>) = rollbackMoveToVault(stagedItems)
    suspend fun removeFromVault(vaultItem: VaultItem): Result<Unit>
    suspend fun restoreToGallery(vaultItem: VaultItem): Result<Uri>
    suspend fun decryptVaultItem(vaultItem: VaultItem): Result<ByteArray>
    suspend fun getDecryptedTempFile(vaultItem: VaultItem): Result<File>
    suspend fun addFileToVault(
        file: File,
        originalName: String,
        mimeType: String,
        isVideo: Boolean,
        albumName: String = "Default"
    ): Result<VaultItem>
    suspend fun getPendingDeleteIntentSender(uris: List<Uri>): IntentSender?
    suspend fun verifyPin(pin: String): Boolean
    suspend fun setPin(pin: String)
    suspend fun hasPin(): Boolean
    fun getThumbnailFile(vaultItem: VaultItem): File?
    suspend fun getThumbnailBytes(vaultItem: VaultItem): ByteArray?
    suspend fun clearCorruptedItems(): Int
    fun canModifyMediaSilently(): Boolean
    suspend fun renameVaultItem(vaultItem: VaultItem, newName: String): Result<String>
    suspend fun computeVaultItemSha256(vaultItem: VaultItem): String?
}
