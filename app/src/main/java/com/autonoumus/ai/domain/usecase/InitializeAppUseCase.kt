package com.autonoumus.ai.domain.usecase

import com.autonoumus.ai.domain.repository.SplashRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class InitializeAppUseCase @Inject constructor(
    private val splashRepository: SplashRepository
) {
    operator fun invoke(): Flow<Int> {
        return splashRepository.initializeApp()
    }
}
