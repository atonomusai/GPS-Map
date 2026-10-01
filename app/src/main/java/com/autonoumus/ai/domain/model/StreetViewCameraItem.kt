package com.autonoumus.ai.domain.model

import androidx.annotation.DrawableRes

data class StreetViewCameraItem(
    val id: String,
    val title: String,
    val coordinates: String = "",
    val altitude: String = "",
    val accuracy: String = "",
    @DrawableRes val imageRes: Int = 0,
    val imageUrl: String? = null
)
