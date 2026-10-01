package com.autonoumus.ai.domain.usecase

import com.autonoumus.ai.domain.model.OnboardingItem
import com.autonoumus.ai.domain.repository.OnboardingRepository
import javax.inject.Inject

class GetOnboardingItemsUseCase @Inject constructor(
    private val onboardingRepository: OnboardingRepository
) {
    operator fun invoke(): List<OnboardingItem> {
        return onboardingRepository.getOnboardingItems()
    }
}
