package com.gallery.core.domain.usecase

import com.gallery.core.domain.model.DuplicateAnalysisResult
import com.gallery.core.domain.model.DuplicateCandidate
import com.gallery.core.domain.model.DuplicateGroup
import com.gallery.core.domain.model.DuplicateGroupType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class AnalyzeDuplicatesUseCase @Inject constructor() {

    suspend fun <T> execute(
        items: List<T>,
        idSelector: (T) -> String,
        nameSelector: (T) -> String,
        isVideoSelector: (T) -> Boolean,
        sizeBytesSelector: (T) -> Long,
        albumOrFolderNameSelector: (T) -> String,
        dateAddedSelector: (T) -> Long,
        openInputStream: (suspend (T) -> InputStream?)? = null,
        computeHash: (suspend (T) -> String?)? = null,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): DuplicateAnalysisResult<T> = withContext(Dispatchers.IO) {
        val total = items.size
        if (total == 0) {
            return@withContext DuplicateAnalysisResult(emptyList(), 0, 0L)
        }

        // Cache computed SHA-256 hex digests by item ID
        val computedHashes = mutableMapOf<String, String>()

        suspend fun getOrComputeHash(item: T, id: String): String? {
            computedHashes[id]?.let { return it }
            coroutineContext.ensureActive()
            val hash = if (computeHash != null) {
                computeHash(item)
            } else if (openInputStream != null) {
                openInputStream(item)?.use { stream ->
                    val md = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        coroutineContext.ensureActive()
                        md.update(buffer, 0, bytesRead)
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
            } else {
                null
            }
            if (hash != null) {
                computedHashes[id] = hash
            }
            return hash
        }

        val initialCandidates = items.map { item ->
            DuplicateCandidate(
                item = item,
                id = idSelector(item),
                name = nameSelector(item),
                isVideo = isVideoSelector(item),
                sizeBytes = sizeBytesSelector(item),
                albumOrFolderName = albumOrFolderNameSelector(item),
                dateAdded = dateAddedSelector(item),
                contentHash = null,
                isRecommendedKeep = false
            )
        }

        var scannedCount = 0
        onProgress(scannedCount, total)

        // Stage 1: Group items with sizeBytes > 0 by (isVideo, sizeBytes)
        val identicalGroups = mutableListOf<DuplicateGroup<T>>()
        val identicalSameNameItemIds = mutableListOf<Set<String>>()

        val sizeBuckets = initialCandidates
            .filter { it.sizeBytes > 0 }
            .groupBy { Pair(it.isVideo, it.sizeBytes) }

        // Compute hash for buckets with count >= 2
        for ((_, bucket) in sizeBuckets) {
            if (bucket.size < 2) continue
            for (cand in bucket) {
                coroutineContext.ensureActive()
                getOrComputeHash(cand.item, cand.id)
                scannedCount++
                onProgress(scannedCount.coerceAtMost(total), total)
            }
        }

        // Group by hash
        for ((_, bucket) in sizeBuckets) {
            if (bucket.size < 2) continue
            coroutineContext.ensureActive()

            val hashBuckets = bucket.mapNotNull { cand ->
                val hash = computedHashes[cand.id] ?: return@mapNotNull null
                cand.copy(contentHash = hash)
            }.groupBy { it.contentHash!! }

            for ((_, hashGroup) in hashBuckets) {
                if (hashGroup.size < 2) continue
                coroutineContext.ensureActive()

                // Oldest item (or first in stable sort) gets isRecommendedKeep = true
                val sortedCandidates = hashGroup.sortedWith(
                    compareBy<DuplicateCandidate<T>> { it.dateAdded }
                        .thenBy { it.id }
                ).mapIndexed { index, cand ->
                    cand.copy(isRecommendedKeep = (index == 0))
                }

                val normalizedNames = sortedCandidates.map { it.name.trim().lowercase() }.toSet()
                val isSameName = normalizedNames.size == 1
                val groupType = if (isSameName) {
                    DuplicateGroupType.IDENTICAL_SAME_NAME
                } else {
                    DuplicateGroupType.IDENTICAL_DIFFERENT_NAME
                }

                val reclaimable = sortedCandidates.first().sizeBytes * (sortedCandidates.size - 1)
                val group = DuplicateGroup(
                    groupId = UUID.randomUUID().toString(),
                    type = groupType,
                    candidates = sortedCandidates,
                    reclaimableBytes = reclaimable
                )
                identicalGroups.add(group)

                if (isSameName) {
                    identicalSameNameItemIds.add(sortedCandidates.map { it.id }.toSet())
                }
            }
        }

        // Stage 2: Same-Name Collision Detection (Non-Identical Content)
        val nameBuckets = initialCandidates
            .filter { it.name.isNotBlank() }
            .groupBy { Pair(it.isVideo, it.name.trim().lowercase()) }

        val sameNameGroups = mutableListOf<DuplicateGroup<T>>()

        for ((_, nameGroup) in nameBuckets) {
            if (nameGroup.size < 2) continue
            coroutineContext.ensureActive()

            val groupIds = nameGroup.map { it.id }.toSet()

            // Exclude pairs/groups that were already completely matched as IDENTICAL_SAME_NAME in Stage 1
            val isAlreadyIdenticalSameName = identicalSameNameItemIds.any { identicalSet ->
                groupIds == identicalSet
            }
            if (isAlreadyIdenticalSameName) {
                continue
            }

            // Check if items have differing file sizes or hashes
            val allSameSize = nameGroup.map { it.sizeBytes }.distinct().size == 1
            if (allSameSize) {
                // Ensure hashes are computed to verify if they are identical
                for (cand in nameGroup) {
                    if (computedHashes[cand.id] == null) {
                        getOrComputeHash(cand.item, cand.id)
                    }
                }
                val hashes = nameGroup.mapNotNull { computedHashes[it.id] }
                if (hashes.size == nameGroup.size && hashes.distinct().size == 1) {
                    // Truly identical copies with same name, already represented or covered
                    continue
                }
            }

            val candidateList = nameGroup.map { cand ->
                cand.copy(
                    contentHash = computedHashes[cand.id],
                    isRecommendedKeep = false
                )
            }

            sameNameGroups.add(
                DuplicateGroup(
                    groupId = UUID.randomUUID().toString(),
                    type = DuplicateGroupType.SAME_NAME_DIFFERENT_CONTENT,
                    candidates = candidateList,
                    reclaimableBytes = 0L
                )
            )
        }

        onProgress(total, total)

        val allGroups = identicalGroups + sameNameGroups
        val totalReclaimable = allGroups.sumOf { it.reclaimableBytes }

        DuplicateAnalysisResult(
            groups = allGroups,
            totalScanned = total,
            totalReclaimableBytes = totalReclaimable
        )
    }
}
