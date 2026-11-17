package com.gallery.core.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.gallery.core.data.db.RecycleBinDao
import com.gallery.core.data.db.RecycleBinItemEntity
import com.gallery.core.domain.model.RecycleBinItem
import com.gallery.core.domain.repository.RecycleBinRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecycleBinRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recycleBinDao: RecycleBinDao
) : RecycleBinRepository {

    override fun getRecycleBinItems(): Flow<List<RecycleBinItem>> =
        recycleBinDao.observeAll().map { entities ->
            entities.map { entity ->
                RecycleBinItem(
                    id = entity.id,
                    originalName = entity.originalName,
                    storedFilePath = entity.storedFilePath.ifEmpty { entity.trashedFilePath },
                    mimeType = entity.mimeType,
                    originalSizeBytes = entity.originalSizeBytes,
                    deletedAt = entity.deletedAt,
                    isVideo = entity.isVideo,
                    originalUri = entity.originalUri,
                    originalMediaId = entity.originalMediaId,
                    bucketName = entity.bucketName,
                    relativePath = entity.relativePath,
                    dateAdded = entity.dateAdded,
                    dateTaken = entity.dateTaken,
                    trashedFilePath = entity.trashedFilePath.ifEmpty { entity.storedFilePath }
                )
            }
        }

    override suspend fun moveToRecycleBin(
        sourceUri: Uri,
        originalName: String,
        mimeType: String,
        isVideo: Boolean,
        mediaId: Long,
        bucketName: String,
        relativePath: String?,
        dateAdded: Long,
        dateTaken: Long?
    ): Result<RecycleBinItem> = withContext(Dispatchers.IO) {
        var resolvedMediaId = mediaId
        if (resolvedMediaId <= 0L) {
            try {
                resolvedMediaId = ContentUris.parseId(sourceUri)
            } catch (_: Exception) {}
        }
        if (resolvedMediaId > 0L) {
            MediaRepositoryImpl.hideMediaIds(listOf(resolvedMediaId))
        }

        try {
            var resolvedName = originalName
            var resolvedMime = mimeType
            var resolvedBucket = bucketName
            var resolvedRelativePath = relativePath
            var resolvedDateAdded = dateAdded
            var resolvedDateTaken = dateTaken

            var diskPath: String? = null

            // Query MediaStore to fill in any missing metadata and retrieve diskPath
            try {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_ADDED,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DATA
                )
                context.contentResolver.query(sourceUri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        if (resolvedMediaId <= 0L) {
                            val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                            if (idCol >= 0) {
                                resolvedMediaId = cursor.getLong(idCol)
                                if (resolvedMediaId > 0L) {
                                    MediaRepositoryImpl.hideMediaIds(listOf(resolvedMediaId))
                                }
                            }
                        }
                        if (resolvedName.isBlank()) {
                            val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                            if (nameCol >= 0) resolvedName = cursor.getString(nameCol) ?: ""
                        }
                        if (resolvedMime.isBlank() || resolvedMime == "video/*") {
                            val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                            if (mimeCol >= 0) resolvedMime = cursor.getString(mimeCol) ?: if (isVideo) "video/mp4" else "image/jpeg"
                        }
                        if (resolvedBucket.isBlank()) {
                            val bucketCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                            if (bucketCol >= 0) resolvedBucket = cursor.getString(bucketCol) ?: ""
                        }
                        if (resolvedDateAdded <= 0L) {
                            val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                            if (dateCol >= 0) resolvedDateAdded = cursor.getLong(dateCol)
                        }
                        val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (dataCol >= 0) {
                            diskPath = cursor.getString(dataCol)
                        }
                        if (resolvedRelativePath.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val relCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                            if (relCol >= 0) resolvedRelativePath = cursor.getString(relCol)
                        }
                        if (resolvedRelativePath.isNullOrBlank() && !diskPath.isNullOrBlank()) {
                            val parent = File(diskPath).parentFile
                            if (parent != null) {
                                resolvedRelativePath = parent.name
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            if (resolvedDateAdded <= 0L) {
                resolvedDateAdded = System.currentTimeMillis() / 1000
            }

            val recycleDir = File(context.filesDir, "recycle_bin").apply { mkdirs() }
            val ext = if (isVideo) ".mp4" else ".jpg"
            val storedFile = File(recycleDir, "${UUID.randomUUID()}$ext")

            var originalSize = 0L
            var movedByRename = false
            val localDiskPath = diskPath

            // Try fast O(1) rename if diskPath is available and accessible
            if (!localDiskPath.isNullOrBlank()) {
                try {
                    val sourceFile = File(localDiskPath)
                    if (sourceFile.exists() && sourceFile.canRead()) {
                        val len = sourceFile.length()
                        if (sourceFile.renameTo(storedFile)) {
                            movedByRename = true
                            originalSize = len
                        }
                    }
                } catch (_: Exception) {
                    movedByRename = false
                }
            }

            // Fall back to copying via ContentResolver using a 256 KB buffer
            if (!movedByRename) {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(storedFile).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            originalSize += read
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                } ?: throw IOException("Cannot read media at URI: $sourceUri")
            }

            // Verify storedFile exists and has positive length
            if (!storedFile.exists() || storedFile.length() <= 0L) {
                throw IOException("Stored file verification failed: exists=${storedFile.exists()}, length=${storedFile.length()}")
            }

            val entity = RecycleBinItemEntity(
                originalName = resolvedName,
                storedFilePath = storedFile.absolutePath,
                mimeType = resolvedMime,
                originalSizeBytes = originalSize,
                deletedAt = System.currentTimeMillis(),
                isVideo = isVideo,
                originalUri = sourceUri.toString(),
                originalMediaId = resolvedMediaId,
                bucketName = resolvedBucket,
                relativePath = resolvedRelativePath,
                dateAdded = resolvedDateAdded,
                dateTaken = resolvedDateTaken,
                trashedFilePath = storedFile.absolutePath
            )
            recycleBinDao.insert(entity)

            // Attempt silent physical delete if permissible
            try {
                if (!movedByRename && !localDiskPath.isNullOrEmpty()) {
                    try {
                        val file = File(localDiskPath)
                        if (file.exists()) {
                            file.delete()
                        }
                    } catch (_: Exception) {}
                }
                context.contentResolver.delete(sourceUri, null, null)
            } catch (_: Exception) {}

            Result.success(
                RecycleBinItem(
                    id = entity.id,
                    originalName = entity.originalName,
                    storedFilePath = entity.storedFilePath,
                    mimeType = entity.mimeType,
                    originalSizeBytes = entity.originalSizeBytes,
                    deletedAt = entity.deletedAt,
                    isVideo = entity.isVideo,
                    originalUri = entity.originalUri,
                    originalMediaId = entity.originalMediaId,
                    bucketName = entity.bucketName,
                    relativePath = entity.relativePath,
                    dateAdded = entity.dateAdded,
                    dateTaken = entity.dateTaken,
                    trashedFilePath = entity.trashedFilePath
                )
            )
        } catch (e: Exception) {
            if (resolvedMediaId > 0L) {
                MediaRepositoryImpl.unhideMediaIds(listOf(resolvedMediaId))
            }
            Result.failure(e)
        }
    }

    override suspend fun restoreFromRecycleBin(item: RecycleBinItem): Result<Uri> =
        withContext(Dispatchers.IO) {
            try {
                val fileName = item.originalName.ifEmpty {
                    if (item.isVideo) "VID_RESTORED_${System.currentTimeMillis()}.mp4"
                    else "IMG_RESTORED_${System.currentTimeMillis()}.jpg"
                }
                val mimeType = item.mimeType.ifEmpty {
                    if (item.isVideo) "video/mp4" else "image/jpeg"
                }

                val originalDateAdded = if (item.dateAdded > 0L) item.dateAdded else (item.deletedAt / 1000)
                val originalDateTaken = item.dateTaken

                // Reconstruct exact original album/folder path
                val relPath = item.relativePath
                val resolvedRelativePath = when {
                    !relPath.isNullOrBlank() -> {
                        val path = relPath.trim()
                        if (path.endsWith("/")) path else "$path/"
                    }
                    item.bucketName.equals("Camera", ignoreCase = true) -> "DCIM/Camera/"
                    item.bucketName.isNotBlank() -> {
                        if (item.isVideo) "Movies/${item.bucketName}/" else "Pictures/${item.bucketName}/"
                    }
                    item.isVideo -> Environment.DIRECTORY_MOVIES + "/Gallery/"
                    else -> Environment.DIRECTORY_PICTURES + "/Gallery/"
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.DATE_ADDED, originalDateAdded)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, originalDateAdded)
                    if (originalDateTaken != null && originalDateTaken > 0L) {
                        put(MediaStore.MediaColumns.DATE_TAKEN, originalDateTaken)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, resolvedRelativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val targetCollection = if (item.isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val restoredUri = context.contentResolver.insert(targetCollection, contentValues)
                    ?: return@withContext Result.failure(IOException("Failed to create MediaStore entry for restored media"))

                val sourceFilePath = item.trashedFilePath.ifEmpty { item.storedFilePath }
                context.contentResolver.openOutputStream(restoredUri)?.use { out ->
                    FileInputStream(File(sourceFilePath)).use { input ->
                        input.copyTo(out)
                    }
                } ?: return@withContext Result.failure(IOException("Failed to write to restored URI: $restoredUri"))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentValues.put(MediaStore.MediaColumns.DATE_ADDED, originalDateAdded)
                    contentValues.put(MediaStore.MediaColumns.DATE_MODIFIED, originalDateAdded)
                    if (originalDateTaken != null && originalDateTaken > 0L) {
                        contentValues.put(MediaStore.MediaColumns.DATE_TAKEN, originalDateTaken)
                    }
                    context.contentResolver.update(restoredUri, contentValues, null, null)
                }

                // Preserve modification time on physical file if available
                try {
                    val projection = arrayOf(MediaStore.MediaColumns.DATA)
                    context.contentResolver.query(restoredUri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val diskPath = cursor.getString(0)
                            if (!diskPath.isNullOrEmpty()) {
                                File(diskPath).setLastModified(originalDateAdded * 1000L)
                            }
                        }
                    }
                } catch (_: Exception) {}

                // Delete stored file from app-private recycle bin storage
                File(sourceFilePath).delete()
                // Delete record from DB
                recycleBinDao.deleteById(item.id)

                // Unhide so it reappears instantly in main gallery
                if (item.originalMediaId > 0L) {
                    MediaRepositoryImpl.unhideMediaIds(listOf(item.originalMediaId))
                }

                // Notify ContentResolver so MediaRepositoryImpl picks up restored file immediately
                try {
                    context.contentResolver.notifyChange(restoredUri, null)
                    context.contentResolver.notifyChange(targetCollection, null)
                } catch (_: Exception) {}

                Result.success(restoredUri)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun uriExistsInMediaStore(uri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null
            )?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveExistingMediaStoreUri(item: RecycleBinItem): Uri? {
        // 1. Try item.originalUri
        if (item.originalUri.isNotBlank()) {
            try {
                val parsed = Uri.parse(item.originalUri)
                if (parsed.scheme == "content" && uriExistsInMediaStore(parsed)) {
                    return parsed
                }
            } catch (_: Exception) {}
        }

        // 2. Try originalMediaId
        val mediaId = item.originalMediaId.takeIf { it > 0L } ?: run {
            try {
                if (item.originalUri.isNotBlank()) ContentUris.parseId(Uri.parse(item.originalUri)) else -1L
            } catch (_: Exception) {
                -1L
            }
        }

        if (mediaId > 0L) {
            val baseUri = if (item.isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val idUri = ContentUris.withAppendedId(baseUri, mediaId)
            if (uriExistsInMediaStore(idUri)) {
                return idUri
            }

            val altBaseUri = if (item.isVideo) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val altIdUri = ContentUris.withAppendedId(altBaseUri, mediaId)
            if (uriExistsInMediaStore(altIdUri)) {
                return altIdUri
            }
        }

        // 3. Fallback: query by DISPLAY_NAME
        if (item.originalName.isNotBlank()) {
            val collections = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )
            for (base in collections) {
                try {
                    context.contentResolver.query(
                        base,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(item.originalName),
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                            if (idCol >= 0) {
                                val foundId = cursor.getLong(idCol)
                                val foundUri = ContentUris.withAppendedId(base, foundId)
                                if (uriExistsInMediaStore(foundUri)) {
                                    return foundUri
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return null
    }

    override suspend fun getPendingPermanentDeleteIntentSender(items: List<RecycleBinItem>): IntentSender? =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext null

            // If MANAGE_EXTERNAL_STORAGE is active on Android 11+, silent deletion is permissible without user consent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                return@withContext null
            }

            val existingUris = mutableListOf<Uri>()
            for (item in items) {
                val uri = resolveExistingMediaStoreUri(item)
                if (uri != null) {
                    existingUris.add(uri)
                }
            }

            if (existingUris.isEmpty()) {
                return@withContext null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    MediaStore.createDeleteRequest(context.contentResolver, existingUris).intentSender
                } catch (e: Exception) {
                    android.util.Log.e("RecycleBinRepo", "createDeleteRequest failed", e)
                    null
                }
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                for (uri in existingUris) {
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: android.app.RecoverableSecurityException) {
                        return@withContext e.userAction.actionIntent.intentSender
                    } catch (_: Exception) {}
                }
                null
            } else {
                null
            }
        }

    override suspend fun deletePermanently(item: RecycleBinItem): Result<Unit> =
        deletePermanently(listOf(item))

    override suspend fun deletePermanently(items: List<RecycleBinItem>): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val idsToUnhide = mutableListOf<Long>()
                var lastError: Throwable? = null

                for (item in items) {
                    val uri = resolveExistingMediaStoreUri(item)
                    var mediaStoreDeleted = true

                    if (uri != null) {
                        // Attempt file-level deletion first if path is queryable
                        try {
                            context.contentResolver.query(
                                uri,
                                arrayOf(MediaStore.MediaColumns.DATA),
                                null,
                                null,
                                null
                            )?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                                    if (dataCol >= 0) {
                                        val diskPath = cursor.getString(dataCol)
                                        if (!diskPath.isNullOrBlank()) {
                                            try {
                                                val f = File(diskPath)
                                                if (f.exists()) f.delete()
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}

                        try {
                            val deletedCount = context.contentResolver.delete(uri, null, null)
                            mediaStoreDeleted = deletedCount > 0 || !uriExistsInMediaStore(uri)
                        } catch (e: Exception) {
                            mediaStoreDeleted = !uriExistsInMediaStore(uri)
                            if (!mediaStoreDeleted) {
                                lastError = e
                            }
                        }
                    }

                    // Only remove the row from recycleBinDao and unhide ID if MediaStore entry is confirmed deleted or no longer exists
                    if (mediaStoreDeleted) {
                        val sourceFilePath = item.trashedFilePath.ifEmpty { item.storedFilePath }
                        if (sourceFilePath.isNotBlank()) {
                            try { File(sourceFilePath).delete() } catch (_: Exception) {}
                        }
                        if (item.storedFilePath.isNotBlank()) {
                            try { File(item.storedFilePath).delete() } catch (_: Exception) {}
                        }
                        recycleBinDao.deleteById(item.id)
                        if (item.originalMediaId > 0L) {
                            idsToUnhide.add(item.originalMediaId)
                        }
                        if (uri != null) {
                            try {
                                context.contentResolver.notifyChange(uri, null)
                                context.contentResolver.notifyChange(
                                    if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    null
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }

                if (idsToUnhide.isNotEmpty()) {
                    MediaRepositoryImpl.unhideMediaIds(idsToUnhide)
                }

                if (lastError != null) {
                    Result.failure(lastError)
                } else {
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun finalizePermanentDelete(items: List<RecycleBinItem>): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val idsToUnhide = mutableListOf<Long>()
                for (item in items) {
                    // 1. Delete app-private stored file from recycle_bin
                    val sourceFilePath = item.trashedFilePath.ifEmpty { item.storedFilePath }
                    if (sourceFilePath.isNotBlank()) {
                        try { File(sourceFilePath).delete() } catch (_: Exception) {}
                    }
                    if (item.storedFilePath.isNotBlank()) {
                        try { File(item.storedFilePath).delete() } catch (_: Exception) {}
                    }

                    // 2. Clean up any residual MediaStore URI if still lingering
                    val uri = resolveExistingMediaStoreUri(item)
                    if (uri != null) {
                        try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                        try {
                            context.contentResolver.notifyChange(uri, null)
                            context.contentResolver.notifyChange(
                                if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                null
                            )
                        } catch (_: Exception) {}
                    }

                    // 3. Delete database record
                    recycleBinDao.deleteById(item.id)
                    if (item.originalMediaId > 0L) {
                        idsToUnhide.add(item.originalMediaId)
                    }
                }

                if (idsToUnhide.isNotEmpty()) {
                    MediaRepositoryImpl.unhideMediaIds(idsToUnhide)
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun emptyRecycleBin(): Result<Unit> =
        withContext(Dispatchers.IO) {
            val allEntities = recycleBinDao.getAll()
            val allItems = allEntities.map { entity ->
                RecycleBinItem(
                    id = entity.id,
                    originalName = entity.originalName,
                    storedFilePath = entity.storedFilePath.ifEmpty { entity.trashedFilePath },
                    mimeType = entity.mimeType,
                    originalSizeBytes = entity.originalSizeBytes,
                    deletedAt = entity.deletedAt,
                    isVideo = entity.isVideo,
                    originalUri = entity.originalUri,
                    originalMediaId = entity.originalMediaId,
                    bucketName = entity.bucketName,
                    relativePath = entity.relativePath,
                    dateAdded = entity.dateAdded,
                    dateTaken = entity.dateTaken,
                    trashedFilePath = entity.trashedFilePath.ifEmpty { entity.storedFilePath }
                )
            }
            deletePermanently(allItems)
        }
}
