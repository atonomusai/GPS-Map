package com.autonoumus.ai.domain.usecase

import com.autonoumus.ai.domain.model.StampSettings
import com.autonoumus.ai.domain.repository.StampSettingsRepository
import javax.inject.Inject

class GetStampSettingsUseCase @Inject constructor(
    private val stampSettingsRepository: StampSettingsRepository
) {
    operator fun invoke(): StampSettings = stampSettingsRepository.getStampSettings()
}
