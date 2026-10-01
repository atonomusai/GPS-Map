package com.autonoumus.ai.domain.usecase

import com.autonoumus.ai.domain.model.QuickToolItem
import com.autonoumus.ai.domain.model.RecentCaptureItem
import com.autonoumus.ai.domain.repository.HomeRepository
import com.autonoumus.ai.domain.repository.MediaCaptureRepository
import javax.inject.Inject

data class HomeData(
    val quickTools: List<QuickToolItem>,
    val recentCaptures: List<RecentCaptureItem>
)

/**
 * Fast, local-only home data (quick tools + recent captures). Street view cameras are fetched
 * separately by [HomeViewModel][com.autonoumus.ai.presentation.home.HomeViewModel] since that
 * call needs a live GPS fix plus a network round-trip and can take several seconds - bundling it
 * here would hold recent captures (an instant local query) back from rendering until it finished.
 */
class GetHomeDataUseCase @Inject constructor(
    private val homeRepository: HomeRepository,
    private val mediaCaptureRepository: MediaCaptureRepository
) {
    suspend operator fun invoke(): HomeData {
        return HomeData(
            quickTools = homeRepository.getQuickTools(),
            recentCaptures = mediaCaptureRepository.getRecentCaptures(limit = 4)
        )
    }
}
