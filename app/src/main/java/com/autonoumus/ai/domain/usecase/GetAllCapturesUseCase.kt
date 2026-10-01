package com.autonoumus.ai.domain.usecase

import com.autonoumus.ai.domain.model.RecentCaptureItem
import com.autonoumus.ai.domain.repository.MediaCaptureRepository
import javax.inject.Inject

class GetAllCapturesUseCase @Inject constructor(
    private val mediaCaptureRepository: MediaCaptureRepository
) {
    suspend operator fun invoke(): List<RecentCaptureItem> = mediaCaptureRepository.getAllCaptures()
}
