package com.autonoumus.ai.data.repository

import com.autonoumus.ai.R
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.domain.model.OnboardingItem
import com.autonoumus.ai.domain.repository.OnboardingRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingRepositoryImpl @Inject constructor(
    private val preferenceManager: PreferenceManager
) : OnboardingRepository {

    override fun getOnboardingItems(): List<OnboardingItem> {
        return listOf(
            OnboardingItem(
                id = 1,
                title = "Photo Timestamp",
                subtitle = "Automatically & manually attach time &\ndate to images you've taken",
                imageRes = R.drawable.onboarding_image_one
            ),
            OnboardingItem(
                id = 2,
                title = "Record video with location",
                subtitle = "Embeds time & GPS location in the video,\nindicating where it was recorded.",
                imageRes = R.drawable.onboarding_iamge_two
            ),
            OnboardingItem(
                id = 3,
                title = "Save Captured",
                subtitle = "Stores & manage photos & videos that\nusers have taken",
                imageRes = R.drawable.obboarding_image_three
            )
        )
    }

    override fun isOnboardingCompleted(): Boolean {
        return preferenceManager.isOnboardingCompleted
    }

    override fun setOnboardingCompleted(completed: Boolean) {
        preferenceManager.isOnboardingCompleted = completed
    }
}
