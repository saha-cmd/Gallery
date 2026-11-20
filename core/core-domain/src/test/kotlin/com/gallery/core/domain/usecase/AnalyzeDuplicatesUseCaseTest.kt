package com.gallery.core.domain.usecase

import com.gallery.core.domain.model.DuplicateGroupType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class AnalyzeDuplicatesUseCaseTest {

    private val useCase = AnalyzeDuplicatesUseCase()

    private data class TestMedia(
        val id: String,
        val name: String,
        val isVideo: Boolean,
        val sizeBytes: Long,
        val album: String,
        val dateAdded: Long,
        val bytes: ByteArray
    )

    private fun runAnalysis(items: List<TestMedia>) = runBlocking {
        useCase.execute(
            items = items,
            idSelector = { it.id },
            nameSelector = { it.name },
            isVideoSelector = { it.isVideo },
            sizeBytesSelector = { it.sizeBytes },
            albumOrFolderNameSelector = { it.album },
            dateAddedSelector = { it.dateAdded },
            openInputStream = { item -> ByteArrayInputStream(item.bytes) }
        )
    }

    @Test
    fun testIdenticalSameNameDetection() {
        val bytes = "identical content bytes".toByteArray()
        val item1 = TestMedia("1", "photo.jpg", isVideo = false, sizeBytes = bytes.size.toLong(), album = "DCIM", dateAdded = 1000L, bytes = bytes)
        val item2 = TestMedia("2", "photo.jpg", isVideo = false, sizeBytes = bytes.size.toLong(), album = "Downloads", dateAdded = 2000L, bytes = bytes)

        val result = runAnalysis(listOf(item1, item2))

        assertEquals(1, result.groups.size)
        val group = result.groups.first()
        assertEquals(DuplicateGroupType.IDENTICAL_SAME_NAME, group.type)
        assertEquals(2, group.candidates.size)
        assertEquals(bytes.size.toLong(), group.reclaimableBytes)
        // Oldest item kept
        assertTrue(group.candidates[0].isRecommendedKeep)
        assertEquals("1", group.candidates[0].id)
        assertFalse(group.candidates[1].isRecommendedKeep)
    }

    @Test
    fun testIdenticalDifferentNamesDetection() {
        val bytes = "same content different names".toByteArray()
        val item1 = TestMedia("1", "original.mp4", isVideo = true, sizeBytes = bytes.size.toLong(), album = "Camera", dateAdded = 500L, bytes = bytes)
        val item2 = TestMedia("2", "copy_backup.mp4", isVideo = true, sizeBytes = bytes.size.toLong(), album = "Backup", dateAdded = 900L, bytes = bytes)

        val result = runAnalysis(listOf(item1, item2))

        assertEquals(1, result.groups.size)
        val group = result.groups.first()
        assertEquals(DuplicateGroupType.IDENTICAL_DIFFERENT_NAME, group.type)
        assertEquals(2, group.candidates.size)
        assertEquals(bytes.size.toLong(), group.reclaimableBytes)
        assertTrue(group.candidates[0].isRecommendedKeep)
        assertEquals("1", group.candidates[0].id)
    }

    @Test
    fun testSameNameDifferentContentDetection() {
        val bytes1 = "photo content A".toByteArray()
        val bytes2 = "photo content B (different length/data)".toByteArray()
        val item1 = TestMedia("1", "image.png", isVideo = false, sizeBytes = bytes1.size.toLong(), album = "Camera", dateAdded = 1000L, bytes = bytes1)
        val item2 = TestMedia("2", "image.png", isVideo = false, sizeBytes = bytes2.size.toLong(), album = "WhatsApp", dateAdded = 1500L, bytes = bytes2)

        val result = runAnalysis(listOf(item1, item2))

        assertEquals(1, result.groups.size)
        val group = result.groups.first()
        assertEquals(DuplicateGroupType.SAME_NAME_DIFFERENT_CONTENT, group.type)
        assertEquals(2, group.candidates.size)
        assertEquals(0L, group.reclaimableBytes)
        // Neither is recommended keep in different content collision
        assertFalse(group.candidates[0].isRecommendedKeep)
        assertFalse(group.candidates[1].isRecommendedKeep)
    }

    @Test
    fun testNeverComparePhotoAgainstVideo() {
        val bytes = "same bytes for photo and video".toByteArray()
        val photo = TestMedia("1", "clip.mp4", isVideo = false, sizeBytes = bytes.size.toLong(), album = "Camera", dateAdded = 1000L, bytes = bytes)
        val video = TestMedia("2", "clip.mp4", isVideo = true, sizeBytes = bytes.size.toLong(), album = "Camera", dateAdded = 1000L, bytes = bytes)

        val result = runAnalysis(listOf(photo, video))

        // Since isVideo differs, photo is never compared against video
        assertEquals(0, result.groups.size)
    }
}
