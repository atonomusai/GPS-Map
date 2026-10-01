package com.autonoumus.ai.data.repository

import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.domain.model.MapTypeOption
import com.autonoumus.ai.domain.model.StampFontStyle
import com.autonoumus.ai.domain.model.StampPosition
import com.autonoumus.ai.domain.model.StampSettings
import com.autonoumus.ai.domain.model.StampTemplate
import com.autonoumus.ai.domain.model.StampTextAlignment
import com.autonoumus.ai.domain.repository.StampSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StampSettingsRepositoryImpl @Inject constructor(
    private val preferenceManager: PreferenceManager
) : StampSettingsRepository {

    override fun getStampSettings(): StampSettings {
        return StampSettings(
            template = StampTemplate.fromNameOrDefault(preferenceManager.stampTemplate),
            showTitleHeader = preferenceManager.stampShowTitleHeader,
            showMapBadge = preferenceManager.stampShowMapBadge,
            mapType = enumFrom(preferenceManager.mapType, MapTypeOption.HYBRID),
            showAddress = preferenceManager.stampShowAddress,
            showTimeZone = preferenceManager.stampShowTimeZone,
            showCoordinates = preferenceManager.stampShowCoordinates,
            opacityPercent = preferenceManager.stampOpacityPercent,
            fontSizePercent = preferenceManager.stampFontSizePercent,
            fontStyle = enumFrom(preferenceManager.stampFontStyle, StampFontStyle.BOLD),
            textColor = preferenceManager.stampTextColor,
            position = enumFrom(preferenceManager.stampPosition, StampPosition.BOTTOM_LEFT),
            textAlignment = enumFrom(preferenceManager.stampTextAlignment, StampTextAlignment.LEFT)
        )
    }

    override fun updateStampTemplate(template: StampTemplate) {
        preferenceManager.stampTemplate = template.name
    }

    private inline fun <reified T : Enum<T>> enumFrom(name: String, default: T): T {
        return try {
            java.lang.Enum.valueOf(T::class.java, name)
        } catch (e: IllegalArgumentException) {
            default
        }
    }
}
