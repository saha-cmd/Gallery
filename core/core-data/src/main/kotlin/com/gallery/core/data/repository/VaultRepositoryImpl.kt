package com.gallery.core.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
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
import com.gallery.core.domain.model.VaultItem
import com.gallery.core.domain.repository.VaultRepository
import com.gallery.core.security.vault.VaultCryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
        albumName: String
    ): Result<VaultItem> = withContext(Dispatchers.IO) {
        try {
            val entityId = UUID.randomUUID().toString()
            val targetAlbum = albumName.ifBlank { "Default" }

            // 1. Pre-generate and cache a 512x512 thumbnail before deleting the source
            saveThumbnailForUri(sourceUri, entityId, isVideo)

            // 2. Encrypt the original file into app-private storage using Envelope Encryption
            val result = cryptoManager.encryptToVault(sourceUri, originalName)
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

            // 3. Mark ID as hidden from main gallery immediately
            try {
                val mediaId = ContentUris.parseId(sourceUri)
                MediaRepositoryImpl.hideMediaIds(listOf(mediaId))
            } catch (_: Exception) {}

            // 4. Delete original completely from device MediaStore and file system
            try {
                val projection = arrayOf(MediaStore.MediaColumns.DATA)
                var diskPath: String? = null
                try {
                    context.contentResolver.query(sourceUri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            diskPath = cursor.getString(0)
                        }
                    }
                } catch (_: Exception) {}

                if (!diskPath.isNullOrEmpty()) {
                    try {
                        val file = File(diskPath!!)
                        if (file.exists()) {
                            file.delete()
                        }
                    } catch (_: Exception) {}
                }

                context.contentResolver.delete(sourceUri, null, null)
            } catch (_: Exception) {}

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

                // Delete cached thumbnail
                val thumbFile = File(context.filesDir, "vault/thumbnails/${vaultItem.id}.jpg")
                if (thumbFile.exists()) thumbFile.delete()

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
                val thumbFile = File(context.filesDir, "vault/thumbnails/${vaultItem.id}.jpg")
                if (thumbFile.exists()) thumbFile.delete()

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
            val entityId = UUID.randomUUID().toString()
            val targetAlbum = albumName.ifBlank { "Default" }
            saveThumbnailForFile(file, entityId, isVideo)

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
        val thumbFile = File(context.filesDir, "vault/thumbnails/${vaultItem.id}.jpg")
        return if (thumbFile.exists() && thumbFile.length() > 0) thumbFile else null
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
                val thumb = File(context.filesDir, "vault/thumbnails/${entity.id}.jpg")
                if (thumb.exists()) thumb.delete()
                val tempJpg = File(context.cacheDir, "vault_secure_temp/vault_temp_${entity.id}.jpg")
                if (tempJpg.exists()) tempJpg.delete()
                val tempMp4 = File(context.cacheDir, "vault_secure_temp/vault_temp_${entity.id}.mp4")
                if (tempMp4.exists()) tempMp4.delete()
                removedCount++
            }
        }
        removedCount
    }

    override suspend fun getPendingDeleteIntentSender(uris: List<Uri>): android.content.IntentSender? =
        withContext(Dispatchers.IO) {
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

    private fun saveThumbnailForUri(sourceUri: Uri, id: String, isVideo: Boolean) {
        try {
            val thumbDir = File(context.filesDir, "vault/thumbnails").apply { mkdirs() }
            val thumbFile = File(thumbDir, "$id.jpg")

            // Modern Android 10+ (API 29+) MediaStore thumbnail API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val bmp = context.contentResolver.loadThumbnail(sourceUri, android.util.Size(512, 512), null)
                    thumbFile.outputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    if (thumbFile.exists() && thumbFile.length() > 0) return
                } catch (e: Exception) {
                    android.util.Log.w("VaultRepo", "loadThumbnail failed, falling back to manual decode", e)
                }
            }

            if (isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, sourceUri)
                    val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                    frame?.let { bmp ->
                        thumbFile.outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                    }
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
                    while (bounds.outWidth / (sampleSize * 2) >= 512 && bounds.outHeight / (sampleSize * 2) >= 512) {
                        sampleSize *= 2
                    }
                }
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    val bmp = BitmapFactory.decodeStream(input, null, decodeOptions)
                    bmp?.let {
                        thumbFile.outputStream().use { out ->
                            it.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VaultRepo", "Thumbnail save error", e)
        }
    }

    private fun saveThumbnailForFile(file: File, id: String, isVideo: Boolean) {
        try {
            val thumbDir = File(context.filesDir, "vault/thumbnails").apply { mkdirs() }
            val thumbFile = File(thumbDir, "$id.jpg")
            if (isVideo) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                retriever.release()
                frame?.let { bmp ->
                    thumbFile.outputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sampleSize = 1
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    while (bounds.outWidth / (sampleSize * 2) >= 512 && bounds.outHeight / (sampleSize * 2) >= 512) {
                        sampleSize *= 2
                    }
                }
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                bmp?.let {
                    thumbFile.outputStream().use { out ->
                        it.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VaultRepo", "Thumbnail file save error", e)
        }
    }
}
