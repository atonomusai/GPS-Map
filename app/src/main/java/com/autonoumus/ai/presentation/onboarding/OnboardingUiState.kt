package com.autonoumus.ai.presentation.onboarding

import com.autonoumus.ai.domain.model.OnboardingItem

data class OnboardingUiState(
    val items: List<OnboardingItem> = emptyList(),
    val currentPage: Int = 0,
    val progressPercent: Float = 33.33f,
    val isLastPage: Boolean = false
)
