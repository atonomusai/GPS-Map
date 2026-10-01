package com.autonoumus.ai.domain.usecase

import com.autonoumus.ai.domain.repository.OnboardingRepository
import javax.inject.Inject

class IsOnboardingCompletedUseCase @Inject constructor(
    private val onboardingRepository: OnboardingRepository
) {
    operator fun invoke(): Boolean {
        return onboardingRepository.isOnboardingCompleted()
    }
}
