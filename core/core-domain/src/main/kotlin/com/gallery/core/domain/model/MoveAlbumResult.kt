package com.gallery.core.domain.model

import android.content.IntentSender

sealed class MoveAlbumResult {
    data class Success(val movedIds: List<Long>, val targetAlbumName: String) : MoveAlbumResult() {
        val movedCount: Int get() = movedIds.size
    }
    data class RequiresPermission(val intentSender: IntentSender, val pendingItems: List<MediaItem>) : MoveAlbumResult()
    data class Error(val message: String) : MoveAlbumResult()
}
