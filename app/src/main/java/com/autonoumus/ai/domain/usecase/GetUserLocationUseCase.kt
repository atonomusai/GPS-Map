package com.autonoumus.ai.domain.usecase

import com.autonoumus.ai.domain.model.UserLocationData
import com.autonoumus.ai.domain.repository.HomeRepository
import javax.inject.Inject

class GetUserLocationUseCase @Inject constructor(
    private val homeRepository: HomeRepository
) {
    suspend operator fun invoke(): UserLocationData {
        return homeRepository.getUserLocation()
    }
}
