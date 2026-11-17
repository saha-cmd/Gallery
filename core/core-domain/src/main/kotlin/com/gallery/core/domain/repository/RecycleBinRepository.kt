package com.gallery.core.domain.repository

import android.content.IntentSender
import android.net.Uri
import com.gallery.core.domain.model.RecycleBinItem
import kotlinx.coroutines.flow.Flow

interface RecycleBinRepository {
    fun getRecycleBinItems(): Flow<List<RecycleBinItem>>
    suspend fun moveToRecycleBin(
        sourceUri: Uri,
        originalName: String,
        mimeType: String,
        isVideo: Boolean,
        mediaId: Long = 0L,
        bucketName: String = "",
        relativePath: String? = null,
        dateAdded: Long = 0L,
        dateTaken: Long? = null
    ): Result<RecycleBinItem>
    suspend fun restoreFromRecycleBin(item: RecycleBinItem): Result<Uri>
    suspend fun deletePermanently(item: RecycleBinItem): Result<Unit>
    suspend fun deletePermanently(items: List<RecycleBinItem>): Result<Unit>
    suspend fun finalizePermanentDelete(items: List<RecycleBinItem>): Result<Unit>
    suspend fun emptyRecycleBin(): Result<Unit>
    suspend fun getPendingPermanentDeleteIntentSender(items: List<RecycleBinItem>): IntentSender?
}
