package com.gallery.core.domain.repository

import android.content.IntentSender
import android.net.Uri
import com.gallery.core.domain.model.VaultItem
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
        albumName: String = "Default"
    ): Result<VaultItem>
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
    suspend fun clearCorruptedItems(): Int
}
