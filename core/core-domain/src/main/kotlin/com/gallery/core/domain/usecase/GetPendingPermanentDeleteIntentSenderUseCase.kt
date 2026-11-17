package com.gallery.core.domain.usecase

import android.content.IntentSender
import com.gallery.core.domain.model.RecycleBinItem
import com.gallery.core.domain.repository.RecycleBinRepository
import javax.inject.Inject

class GetPendingPermanentDeleteIntentSenderUseCase @Inject constructor(
    private val repository: RecycleBinRepository
) {
    suspend operator fun invoke(items: List<RecycleBinItem>): IntentSender? =
        repository.getPendingPermanentDeleteIntentSender(items)
}
