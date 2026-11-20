package com.gallery.core.domain.model

import android.content.IntentSender

sealed interface RenameMediaResult {
    data class Success(val newName: String) : RenameMediaResult
    data class RequiresPermission(
        val intentSender: IntentSender,
        val item: MediaItem,
        val newName: String
    ) : RenameMediaResult
    data class Error(val message: String) : RenameMediaResult
}
