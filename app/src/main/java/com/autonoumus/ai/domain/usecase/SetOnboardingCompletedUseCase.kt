package com.autonoumus.ai.domain.usecase

import com.autonoumus.ai.domain.repository.OnboardingRepository
import javax.inject.Inject

class SetOnboardingCompletedUseCase @Inject constructor(
    private val onboardingRepository: OnboardingRepository
) {
    operator fun invoke(completed: Boolean = true) {
        onboardingRepository.setOnboardingCompleted(completed)
    }
}
