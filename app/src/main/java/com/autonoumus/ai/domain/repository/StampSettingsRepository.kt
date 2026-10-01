package com.autonoumus.ai.domain.repository

import com.autonoumus.ai.domain.model.StampSettings
import com.autonoumus.ai.domain.model.StampTemplate

interface StampSettingsRepository {
    fun getStampSettings(): StampSettings
    fun updateStampTemplate(template: StampTemplate)
}
