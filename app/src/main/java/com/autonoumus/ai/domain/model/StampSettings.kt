package com.autonoumus.ai.domain.model

import android.graphics.Color

data class StampSettings(
    val template: StampTemplate = StampTemplate.GPS_FULL_CARD,
    val showTitleHeader: Boolean = true,
    val showMapBadge: Boolean = true,
    val mapType: MapTypeOption = MapTypeOption.NORMAL,
    val showAddress: Boolean = false,
    val showTimeZone: Boolean = false,
    val showCoordinates: Boolean = true,
    val opacityPercent: Int = 50,
    val fontSizePercent: Int = 100,
    val fontStyle: StampFontStyle = StampFontStyle.BOLD,
    val textColor: Int = Color.WHITE,
    val position: StampPosition = StampPosition.BOTTOM_LEFT,
    val textAlignment: StampTextAlignment = StampTextAlignment.LEFT
)
