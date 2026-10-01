package com.autonoumus.ai.domain.repository

import com.autonoumus.ai.domain.model.OnboardingItem

interface OnboardingRepository {
    fun getOnboardingItems(): List<OnboardingItem>
    fun isOnboardingCompleted(): Boolean
    fun setOnboardingCompleted(completed: Boolean)
}
