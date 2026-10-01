package com.autonoumus.ai.domain.model

import androidx.annotation.DrawableRes

data class QuickToolItem(
    val id: String,
    val title: String,
    @DrawableRes val iconRes: Int
)
