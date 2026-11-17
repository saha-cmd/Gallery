package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.SettingsRepository
import javax.inject.Inject

class SetDefaultGridColumnsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(columns: Int) = settingsRepository.setDefaultGridColumns(columns)
}
