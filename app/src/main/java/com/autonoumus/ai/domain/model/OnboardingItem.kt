package com.autonoumus.ai.domain.model

import androidx.annotation.DrawableRes

data class OnboardingItem(
    val id: Int,
    val title: String,
    val subtitle: String,
    @DrawableRes val imageRes: Int
)
