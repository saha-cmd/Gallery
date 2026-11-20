package com.gallery.core.domain.model

import android.content.IntentSender
import android.net.Uri

data class StagedVaultItem(
    val id: String,
    val sourceUri: Uri,
    val sourceMediaId: Long = 0L,
    val originalName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val albumName: String,
    val encryptedFilePath: String,
    val iv: ByteArray,
    val originalSizeBytes: Long,
    val thumbnailFilePath: String? = null,
    val diskPath: String? = null
)

typealias PendingVaultItem = StagedVaultItem

data class VaultMoveRequest(
    val sourceUri: Uri,
    val originalName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val albumName: String = "Default",
    val mediaId: Long = 0L
)

sealed class VaultMoveResult {
    data class Success(val items: List<VaultItem>) : VaultMoveResult()
    data class RequiresConsent(
        val intentSender: IntentSender,
        val pendingItems: List<StagedVaultItem>
    ) : VaultMoveResult() {
        val stagedItems: List<StagedVaultItem> get() = pendingItems
    }
    data class Failure(val message: String, val cause: Throwable? = null) : VaultMoveResult()
}
