package com.gallery.core.domain.usecase

import android.net.Uri
import com.gallery.core.domain.model.RecycleBinItem
import com.gallery.core.domain.repository.RecycleBinRepository
import javax.inject.Inject

class MoveToRecycleBinUseCase @Inject constructor(
    private val repository: RecycleBinRepository
) {
    suspend operator fun invoke(
        sourceUri: Uri,
        originalName: String,
        mimeType: String,
        isVideo: Boolean,
        mediaId: Long = 0L,
        bucketName: String = "",
        relativePath: String? = null,
        dateAdded: Long = 0L,
        dateTaken: Long? = null
    ): Result<RecycleBinItem> = repository.moveToRecycleBin(
        sourceUri = sourceUri,
        originalName = originalName,
        mimeType = mimeType,
        isVideo = isVideo,
        mediaId = mediaId,
        bucketName = bucketName,
        relativePath = relativePath,
        dateAdded = dateAdded,
        dateTaken = dateTaken
    )
}
