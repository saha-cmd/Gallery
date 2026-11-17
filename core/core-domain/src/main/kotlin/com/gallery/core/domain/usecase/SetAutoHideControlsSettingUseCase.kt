package com.gallery.core.domain.usecase

import com.gallery.core.domain.repository.SettingsRepository
import javax.inject.Inject

class SetAutoHideControlsSettingUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(enabled: Boolean) = settingsRepository.setAutoHideControlsEnabled(enabled)
}
