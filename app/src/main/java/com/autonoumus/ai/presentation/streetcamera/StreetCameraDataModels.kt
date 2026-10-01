package com.autonoumus.ai.presentation.streetcamera

import androidx.annotation.DrawableRes

data class PlaceCategoryModel(
    val id: String,
    val title: String,
    @DrawableRes val iconRes: Int,
    val bgTintHex: String,
    val iconTintHex: String
)

data class CountryCameraModel(
    val isoCode: String,
    val name: String,
    val cameraCountLabel: String,
    @DrawableRes val flagDrawableRes: Int
)

data class PopularCityLocationModel(
    val id: String,
    val name: String,
    val stateOrRegion: String,
    val country: String,
    val cameraCount: Int,
    val imageUrl: String,
    val categoryType: String = "Cities",
    val isLive: Boolean = true
) {
    val fullDisplayName: String
        get() = if (stateOrRegion.isNotBlank()) "$name , $stateOrRegion" else name

    // The per-city counts were never real data; don't show them as camera counts.
    val cameraCountLabel: String
        get() = "View webcams"
}

data class FeaturedLocationModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val cameraCountLabel: String,
    val imageUrl: String,
    val isLive: Boolean = true
)
