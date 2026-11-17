package com.gallery.core.domain.usecase

import android.net.Uri
import com.gallery.core.domain.model.RecycleBinItem
import com.gallery.core.domain.repository.RecycleBinRepository
import javax.inject.Inject

class RestoreFromRecycleBinUseCase @Inject constructor(
    private val repository: RecycleBinRepository
) {
    suspend operator fun invoke(item: RecycleBinItem): Result<Uri> =
        repository.restoreFromRecycleBin(item)
}
