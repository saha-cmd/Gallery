package com.gallery.core.domain.model

enum class DuplicateGroupType {
    IDENTICAL_SAME_NAME,
    IDENTICAL_DIFFERENT_NAME,
    SAME_NAME_DIFFERENT_CONTENT
}

data class DuplicateCandidate<T>(
    val item: T,
    val id: String,
    val name: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val albumOrFolderName: String,
    val dateAdded: Long,
    val contentHash: String?,
    val isRecommendedKeep: Boolean = false
)

data class DuplicateGroup<T>(
    val groupId: String,
    val type: DuplicateGroupType,
    val candidates: List<DuplicateCandidate<T>>,
    val reclaimableBytes: Long
)

data class DuplicateAnalysisResult<T>(
    val groups: List<DuplicateGroup<T>>,
    val totalScanned: Int,
    val totalReclaimableBytes: Long
)
