package com.gallery.core.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gallery.core.data.db.VaultDao
import com.gallery.core.data.db.VaultItemEntity
import com.gallery.core.data.permissions.MediaPermissionManager
import com.gallery.core.domain.model.PendingVaultItem
import com.gallery.core.domain.model.StagedVaultItem
import com.gallery.core.domain.model.VaultItem
import com.gallery.core.domain.model.VaultMoveRequest
import com.gallery.core.domain.model.VaultMoveResult
import com.gallery.core.domain.repository.VaultRepository
import com.gallery.core.security.vault.VaultCryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vault_prefs")

@Singleton
class VaultRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultDao: VaultDao,
    private val cryptoManager: VaultCryptoManager
) : VaultRepository {

    companion object {
        private val PIN_KEY = stringPreferencesKey("vault_pin_hash")
    }

    init {
        // Initialize .nomedia markers and clean up any legacy unencrypted thumbnails
        cleanupLegacyThumbnailsAndEnsureNomedia()
    }

    private fun cleanupLegacyThumbnailsAndEnsureNomedia() {
        cryptoManager.ensureNomediaMarkers()
        try {
            val thumbDir = File(context.filesDir, "vault/thumbnails")
            if (thumbDir.exists()) {
                val legacyFiles = thumbDir.listFiles { _, name -> name.endsWith(".jpg", ignoreCase = true) }
                legacyFiles?.forEach { file ->
                    try { file.delete() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    override fun getVaultItems(): Flow<List<VaultItem>> =
        vaultDao.observeAll().map { entities ->
            entities.map { entity ->
                VaultItem(
                    id = entity.id,
                    originalName = entity.originalName,
                    encryptedFilePath = entity.encryptedFilePath,
                    iv = entity.iv,
                    mimeType = entity.mimeType,
                    originalSizeBytes = entity.originalSizeBytes,
                    addedAt = entity.addedAt,
                    isVideo = entity.isVideo,
                    albumName = entity.albumName
                )
            }
        }

    override fun getVaultAlbumNames(): Flow<List<String>> = vaultDao.observeAllAlbumNames()

    override suspend fun getExistingAlbumNames(): List<String> = withContext(Dispatchers.IO) {
        vaultDao.getAllAlbumNames()
    }

    override suspend fun addToVault(
        sourceUri: Uri,
        originalName: String,
        mimeType: String,
        isVideo: Boolean,
        albumName: String,
        mediaId: Long
    ): VaultMoveResult = withContext(Dispatchers.IO) {
        moveMultipleToVault(
            listOf(
                VaultMoveRequest(
                    sourceUri = sourceUri,
                    originalName = originalName,
                    mimeType = mimeType,
                    isVideo = isVideo,
                    albumName = albumName,
                    mediaId = mediaId
                )
            )
        )
    }

    override suspend fun moveMultipleToVault(requests: List<VaultMoveRequest>): VaultMoveResult =
        withContext(Dispatchers.IO) {
            if (requests.isEmpty()) {
                return@withContext VaultMoveResult.Success(emptyList())
            }

            cleanupLegacyThumbnailsAndEnsureNomedia()

            val stagedItems = mutableListOf<StagedVaultItem>()
            val processedEncryptedFiles = mutableListOf<File>()
            val processedThumbFiles = mutableListOf<File>()

            try {
                // Phase 1: Encrypt each media stream into app-private .vault BEFORE touching or deleting anything
                for (req in requests) {
                    val entityId = UUID.randomUUID().toString()
                    val targetAlbum = req.albumName.ifBlank { "Default" }

                    // Query diskPath from MediaStore
                    var diskPath: String? = null
                    try {
                        val projection = arrayOf(MediaStore.MediaColumns.DATA)
                        context.contentResolver.query(req.sourceUri, projection, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                diskPath = cursor.getString(0)
                            }
                        }
                    } catch (_: Exception) {}

                    // Pre-generate encrypted thumbnail (.vthumb)
                    val thumbDir = File(context.filesDir, "vault/thumbnails").apply { mkdirs() }
                    val vthumbFile = File(thumbDir, "$entityId.vthumb")
                    saveEncryptedThumbnailForUri(req.sourceUri, vthumbFile, req.isVideo)
                    if (vthumbFile.exists() && vthumbFile.length() > 0) {
                        processedThumbFiles.add(vthumbFile)
                    }

                    // Encrypt original media into .vault file
                    val encResult = cryptoManager.encryptToVault(req.sourceUri, req.originalName)
                    val encFile = File(encResult.encryptedFilePath)
                    if (!encFile.exists() || encFile.length() <= 0L) {
                        throw IOException("Encrypted vault file is missing or zero bytes: ${encResult.encryptedFilePath}")
                    }
                    processedEncryptedFiles.add(encFile)

                    val resolvedMediaId = if (req.mediaId > 0L) {
                        req.mediaId
                    } else {
                        try { ContentUris.parseId(req.sourceUri) } catch (_: Exception) { 0L }
                    }

                    stagedItems.add(
                        StagedVaultItem(
                            id = entityId,
                            sourceUri = req.sourceUri,
                            sourceMediaId = resolvedMediaId,
                            originalName = req.originalName,
                            mimeType = req.mimeType,
                            isVideo = req.isVideo,
                            albumName = targetAlbum,
                            encryptedFilePath = encResult.encryptedFilePath,
                            iv = encResult.iv,
                            originalSizeBytes = encResult.originalSize,
                            thumbnailFilePath = if (vthumbFile.exists()) vthumbFile.absolutePath else null,
                            diskPath = diskPath
                        )
                    )
                }

                // Phase 1B: Direct silent deletion if canModifyMediaSilently() is true
                if (canModifyMediaSilently()) {
                    val committedItems = commitPendingItems(stagedItems)
                    for (item in stagedItems) {
                        try {
                            if (!item.diskPath.isNullOrEmpty()) {
                                val file = File(item.diskPath)
                                if (file.exists()) file.delete()
                                android.media.MediaScannerConnection.scanFile(context, arrayOf(item.diskPath), null, null)
                            }
                        } catch (_: Exception) {}
                        try {
                            context.contentResolver.delete(item.sourceUri, null, null)
                        } catch (_: Exception) {}
                    }
                    return@withContext VaultMoveResult.Success(committedItems)
                }

                val uris = stagedItems.map { it.sourceUri }

                // Try silent deletion via ContentResolver
                var directDeleteSucceeded = false
                var recoverableIntentSender: IntentSender? = null

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    var allDeleted = true
                    for (item in stagedItems) {
                        try {
                            if (!item.diskPath.isNullOrEmpty()) {
                                val file = File(item.diskPath)
                                if (file.exists()) file.delete()
                            }
                            val count = context.contentResolver.delete(item.sourceUri, null, null)
                            if (count <= 0) {
                                allDeleted = false
                            }
                        } catch (e: Exception) {
                            allDeleted = false
                            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                                e is android.app.RecoverableSecurityException) {
                                recoverableIntentSender = e.userAction.actionIntent.intentSender
                            }
                        }
                    }
                    directDeleteSucceeded = allDeleted
                } else {
                    // Android 11+: Attempt silent delete (succeeds if app owns the MediaStore items)
                    try {
                        var allDeleted = true
                        for (item in stagedItems) {
                            val count = context.contentResolver.delete(item.sourceUri, null, null)
                            if (count <= 0) {
                                allDeleted = false
                                break
                            }
                        }
                        directDeleteSucceeded = allDeleted
                    } catch (_: SecurityException) {
                        directDeleteSucceeded = false
                    } catch (_: Exception) {
                        directDeleteSucceeded = false
                    }
                }

                if (directDeleteSucceeded) {
                    val committedItems = commitPendingItems(stagedItems)
                    return@withContext VaultMoveResult.Success(committedItems)
                }

                // Direct deletion failed or requires user consent
                val deleteIntentSender: IntentSender? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    recoverableIntentSender
                }

                if (deleteIntentSender != null) {
                    return@withContext VaultMoveResult.RequiresConsent(
                        intentSender = deleteIntentSender,
                        pendingItems = stagedItems
                    )
                } else {
                    // Cannot delete and cannot request consent; rollback to prevent duplicates
                    rollbackMoveToVault(stagedItems)
                    return@withContext VaultMoveResult.Failure("Cannot delete original files from shared storage")
                }
            } catch (e: Exception) {
                // Rollback on any failure during encryption
                for (f in processedEncryptedFiles) {
                    try { if (f.exists()) f.delete() } catch (_: Exception) {}
                }
                for (f in processedThumbFiles) {
                    try { if (f.exists()) f.delete() } catch (_: Exception) {}
                }
                return@withContext VaultMoveResult.Failure("Failed to secure items: ${e.message}", e)
            }
        }

    private suspend fun commitPendingItems(pendingItems: List<StagedVaultItem>): List<VaultItem> {
        val committed = mutableListOf<VaultItem>()
        val mediaIdsToHide = mutableListOf<Long>()

        for (item in pendingItems) {
            val entity = VaultItemEntity(
                id = item.id,
                originalName = item.originalName,
                encryptedFilePath = item.encryptedFilePath,
                iv = item.iv,
                mimeType = item.mimeType,
                originalSizeBytes = item.originalSizeBytes,
                isVideo = item.isVideo,
                albumName = item.albumName
            )
            vaultDao.insert(entity)

            if (item.sourceMediaId > 0L) {
                mediaIdsToHide.add(item.sourceMediaId)
            } else {
                try {
                    val id = ContentUris.parseId(item.sourceUri)
                    mediaIdsToHide.add(id)
                } catch (_: Exception) {}
            }

            committed.add(
                VaultItem(
                    id = entity.id,
                    originalName = entity.originalName,
                    encryptedFilePath = entity.encryptedFilePath,
                    iv = entity.iv,
                    mimeType = entity.mimeType,
                    originalSizeBytes = entity.originalSizeBytes,
                    addedAt = entity.addedAt,
                    isVideo = entity.isVideo,
                    albumName = entity.albumName
                )
            )
        }

        if (mediaIdsToHide.isNotEmpty()) {
            MediaRepositoryImpl.hideMediaIds(mediaIdsToHide)
        }

        return committed
    }

    override suspend fun finalizeMoveToVault(pendingItems: List<StagedVaultItem>): Result<List<VaultItem>> =
        withContext(Dispatchers.IO) {
            try {
                // Android delete dialog has already deleted the MediaStore item upon user tapping Yes.
                // Finalize cleaning any residual physical disk file if accessible.
                for (item in pendingItems) {
                    if (!item.diskPath.isNullOrEmpty()) {
                        try {
                            val f = File(item.diskPath)
                            if (f.exists()) f.delete()
                        } catch (_: Exception) {}
                    }
                }

                val committed = commitPendingItems(pendingItems)
                Result.success(committed)
            } catch (e: Exception) {
                rollbackMoveToVault(pendingItems)
                Result.failure(e)
            }
        }

    override suspend fun rollbackMoveToVault(pendingItems: List<StagedVaultItem>) =
        withContext(Dispatchers.IO) {
            val mediaIdsToUnhide = mutableListOf<Long>()
            for (item in pendingItems) {
                try {
                    val encFile = File(item.encryptedFilePath)
                    if (encFile.exists()) encFile.delete()
                } catch (_: Exception) {}

                try {
                    item.thumbnailFilePath?.let { path ->
                        val thumbFile = File(path)
                        if (thumbFile.exists()) thumbFile.delete()
                    }
                    val vthumb = File(context.filesDir, "vault/thumbnails/${item.id}.vthumb")
                    if (vthumb.exists()) vthumb.delete()
                } catch (_: Exception) {}

                try {
                    vaultDao.deleteById(item.id)
                } catch (_: Exception) {}

                if (item.sourceMediaId > 0L) {
                    mediaIdsToUnhide.add(item.sourceMediaId)
                } else {
                    try {
                        val id = ContentUris.parseId(item.sourceUri)
                        mediaIdsToUnhide.add(id)
                    } catch (_: Exception) {}
                }
            }

            if (mediaIdsToUnhide.isNotEmpty()) {
                MediaRepositoryImpl.unhideMediaIds(mediaIdsToUnhide)
            }
        }

    override suspend fun removeFromVault(vaultItem: VaultItem): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val entity = VaultItemEntity(
                    id = vaultItem.id,
                    originalName = vaultItem.originalName,
                    encryptedFilePath = vaultItem.encryptedFilePath,
                    iv = vaultItem.iv,
                    mimeType = vaultItem.mimeType,
                    originalSizeBytes = vaultItem.originalSizeBytes,
                    addedAt = vaultItem.addedAt,
                    isVideo = vaultItem.isVideo
                )
                vaultDao.delete(entity)

                // Delete encrypted file
                File(vaultItem.encryptedFilePath).delete()

                // Delete encrypted thumbnail
                val vthumbFile = File(context.filesDir, "vault/thumbnails/${vaultItem.id}.vthumb")
                if (vthumbFile.exists()) vthumbFile.delete()

                // Delete legacy thumbnail if present
                val legacyThumb = File(context.filesDir, "vault/thumbnails/${vaultItem.id}.jpg")
                if (legacyThumb.exists()) legacyThumb.delete()

                // Delete temp viewing file if exists
                val tempJpg = File(context.cacheDir, "vault_secure_temp/vault_temp_${vaultItem.id}.jpg")
                if (tempJpg.exists()) tempJpg.delete()
                val tempMp4 = File(context.cacheDir, "vault_secure_temp/vault_temp_${vaultItem.id}.mp4")
                if (tempMp4.exists()) tempMp4.delete()

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun restoreToGallery(vaultItem: VaultItem): Result<Uri> =
        withContext(Dispatchers.IO) {
            try {
                val fileName = vaultItem.originalName.ifEmpty {
                    if (vaultItem.isVideo) "VID_RESTORED_${System.currentTimeMillis()}.mp4"
                    else "IMG_RESTORED_${System.currentTimeMillis()}.jpg"
                }
                val mimeType = vaultItem.mimeType.ifEmpty {
                    if (vaultItem.isVideo) "video/mp4" else "image/jpeg"
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relativePath = if (vaultItem.isVideo) {
                            Environment.DIRECTORY_MOVIES + "/Gallery"
                        } else {
                            Environment.DIRECTORY_PICTURES + "/Gallery"
                        }
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val targetCollection = if (vaultItem.isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val restoredUri = context.contentResolver.insert(targetCollection, contentValues)
                    ?: return@withContext Result.failure(IOException("Failed to create MediaStore entry for restored media"))

                context.contentResolver.openOutputStream(restoredUri)?.use { out ->
                    cryptoManager.decryptToStream(vaultItem.encryptedFilePath, vaultItem.iv, out)
                } ?: return@withContext Result.failure(IOException("Failed to write to restored URI: $restoredUri"))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(restoredUri, contentValues, null, null)
                }

                // Delete encrypted file and thumbnail from vault storage
                File(vaultItem.encryptedFilePath).delete()
                val vthumbFile = File(context.filesDir, "vault/thumbnails/${vaultItem.id}.vthumb")
                if (vthumbFile.exists()) vthumbFile.delete()
                val legacyThumb = File(context.filesDir, "vault/thumbnails/${vaultItem.id}.jpg")
                if (legacyThumb.exists()) legacyThumb.delete()

                // Delete record from database
                vaultDao.deleteById(vaultItem.id)

                Result.success(restoredUri)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun decryptVaultItem(vaultItem: VaultItem): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = cryptoManager.decryptFromVault(vaultItem.encryptedFilePath, vaultItem.iv)
                Result.success(bytes)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getDecryptedTempFile(vaultItem: VaultItem): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val cacheFolder = File(context.cacheDir, "vault_secure_temp").apply { mkdirs() }
                cryptoManager.ensureNomediaMarkers()
                val ext = if (vaultItem.isVideo) ".mp4" else ".jpg"
                val tempFile = File(cacheFolder, "vault_temp_${vaultItem.id}$ext")
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    val workFile = File(cacheFolder, "vault_work_${vaultItem.id}.tmp")
                    if (workFile.exists()) workFile.delete()
                    cryptoManager.decryptToFile(vaultItem.encryptedFilePath, vaultItem.iv, workFile)
                    if (workFile.exists() && workFile.length() > 0) {
                        if (tempFile.exists()) tempFile.delete()
                        if (!workFile.renameTo(tempFile)) {
                            workFile.copyTo(tempFile, overwrite = true)
                            workFile.delete()
                        }
                    } else {
                        throw IOException("Decrypted file was empty")
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    Result.success(tempFile)
                } else {
                    Result.failure(IOException("Decrypted file missing"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun addFileToVault(
        file: File,
        originalName: String,
        mimeType: String,
        isVideo: Boolean,
        albumName: String
    ): Result<VaultItem> = withContext(Dispatchers.IO) {
        try {
            cleanupLegacyThumbnailsAndEnsureNomedia()
            val entityId = UUID.randomUUID().toString()
            val targetAlbum = albumName.ifBlank { "Default" }

            val thumbDir = File(context.filesDir, "vault/thumbnails").apply { mkdirs() }
            val vthumbFile = File(thumbDir, "$entityId.vthumb")
            saveEncryptedThumbnailForFile(file, vthumbFile, isVideo)

            val result = cryptoManager.encryptFileToVault(file, originalName)
            val entity = VaultItemEntity(
                id = entityId,
                originalName = originalName,
                encryptedFilePath = result.encryptedFilePath,
                iv = result.iv,
                mimeType = mimeType,
                originalSizeBytes = result.originalSize,
                isVideo = isVideo,
                albumName = targetAlbum
            )
            vaultDao.insert(entity)
            file.delete()
            Result.success(
                VaultItem(
                    id = entity.id,
                    originalName = entity.originalName,
                    encryptedFilePath = entity.encryptedFilePath,
                    iv = entity.iv,
                    mimeType = entity.mimeType,
                    originalSizeBytes = entity.originalSizeBytes,
                    addedAt = entity.addedAt,
                    isVideo = entity.isVideo,
                    albumName = entity.albumName
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getThumbnailFile(vaultItem: VaultItem): File? {
        // Plaintext thumbnails on disk are prohibited; returns null to enforce in-memory preview
        return null
    }

    override suspend fun getThumbnailBytes(vaultItem: VaultItem): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val thumbDir = File(context.filesDir, "vault/thumbnails")
                val vthumbFile = File(thumbDir, "${vaultItem.id}.vthumb")
                if (vthumbFile.exists() && vthumbFile.length() > 0) {
                    return@withContext cryptoManager.decryptBytesFromVaultFile(vthumbFile)
                }

                // If .vthumb does not exist, decrypt original media to stream and generate encrypted .vthumb
                val rawBytes = cryptoManager.decryptFromVault(vaultItem.encryptedFilePath, vaultItem.iv)
                if (rawBytes.isNotEmpty()) {
                    val bmp = if (vaultItem.isVideo) {
                        null // For video without vthumb, skip or generate on demand
                    } else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
                        var sampleSize = 1
                        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                            while (bounds.outWidth / (sampleSize * 2) >= 256 && bounds.outHeight / (sampleSize * 2) >= 256) {
                                sampleSize *= 2
                            }
                        }
                        val decodeOpts = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                    }

                    if (bmp != null) {
                        val bos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                        val thumbBytes = bos.toByteArray()
                        bmp.recycle()
                        vthumbFile.parentFile?.mkdirs()
                        cryptoManager.encryptBytesToVaultFile(thumbBytes, vthumbFile)
                        return@withContext thumbBytes
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun clearCorruptedItems(): Int = withContext(Dispatchers.IO) {
        var removedCount = 0
        val allEntities = vaultDao.getAllEntities()
        for (entity in allEntities) {
            val vaultFile = File(entity.encryptedFilePath)
            var isHealthy = false
            if (vaultFile.exists() && vaultFile.length() >= 5) {
                val tempTestFile = File(context.cacheDir, "vault_test_${entity.id}.tmp")
                try {
                    cryptoManager.decryptToFile(entity.encryptedFilePath, entity.iv, tempTestFile)
                    isHealthy = tempTestFile.exists() && tempTestFile.length() > 0
                } catch (_: Exception) {
                    isHealthy = false
                } finally {
                    if (tempTestFile.exists()) tempTestFile.delete()
                }
            }
            if (!isHealthy) {
                vaultDao.deleteById(entity.id)
                if (vaultFile.exists()) vaultFile.delete()
                val vthumb = File(context.filesDir, "vault/thumbnails/${entity.id}.vthumb")
                if (vthumb.exists()) vthumb.delete()
                val legacyThumb = File(context.filesDir, "vault/thumbnails/${entity.id}.jpg")
                if (legacyThumb.exists()) legacyThumb.delete()
                val tempJpg = File(context.cacheDir, "vault_secure_temp/vault_temp_${entity.id}.jpg")
                if (tempJpg.exists()) tempJpg.delete()
                val tempMp4 = File(context.cacheDir, "vault_secure_temp/vault_temp_${entity.id}.mp4")
                if (tempMp4.exists()) tempMp4.delete()
                removedCount++
            }
        }
        removedCount
    }

    override fun canModifyMediaSilently(): Boolean =
        MediaPermissionManager.canModifyMediaSilently(context)

    override suspend fun getPendingDeleteIntentSender(uris: List<Uri>): IntentSender? =
        withContext(Dispatchers.IO) {
            if (canModifyMediaSilently()) {
                return@withContext null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.isNotEmpty()) {
                try {
                    MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }

    override suspend fun verifyPin(pin: String): Boolean {
        val storedHash = context.dataStore.data.first()[PIN_KEY] ?: return false
        return hashPin(pin) == storedHash
    }

    override suspend fun setPin(pin: String) {
        context.dataStore.edit { prefs ->
            prefs[PIN_KEY] = hashPin(pin)
        }
    }

    override suspend fun hasPin(): Boolean {
        return context.dataStore.data.first()[PIN_KEY] != null
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun saveEncryptedThumbnailForUri(sourceUri: Uri, destinationVthumb: File, isVideo: Boolean) {
        try {
            var thumbBitmap: Bitmap? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    thumbBitmap = context.contentResolver.loadThumbnail(sourceUri, android.util.Size(320, 320), null)
                } catch (_: Exception) {}
            }

            if (thumbBitmap == null) {
                if (isVideo) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, sourceUri)
                        thumbBitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: retriever.frameAtTime
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, bounds)
                    }
                    var sampleSize = 1
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        while (bounds.outWidth / (sampleSize * 2) >= 320 && bounds.outHeight / (sampleSize * 2) >= 320) {
                            sampleSize *= 2
                        }
                    }
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        thumbBitmap = BitmapFactory.decodeStream(input, null, decodeOptions)
                    }
                }
            }

            thumbBitmap?.let { bmp ->
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                val bytes = bos.toByteArray()
                bmp.recycle()
                cryptoManager.encryptBytesToVaultFile(bytes, destinationVthumb)
            }
        } catch (e: Exception) {
            android.util.Log.e("VaultRepo", "Encrypted thumbnail error", e)
        }
    }

    private suspend fun saveEncryptedThumbnailForFile(file: File, destinationVthumb: File, isVideo: Boolean) {
        try {
            var thumbBitmap: Bitmap? = null
            if (isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    thumbBitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sampleSize = 1
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    while (bounds.outWidth / (sampleSize * 2) >= 320 && bounds.outHeight / (sampleSize * 2) >= 320) {
                        sampleSize *= 2
                    }
                }
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                thumbBitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            }

            thumbBitmap?.let { bmp ->
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                val bytes = bos.toByteArray()
                bmp.recycle()
                cryptoManager.encryptBytesToVaultFile(bytes, destinationVthumb)
            }
        } catch (e: Exception) {
            android.util.Log.e("VaultRepo", "Encrypted thumbnail file error", e)
        }
    }
}
