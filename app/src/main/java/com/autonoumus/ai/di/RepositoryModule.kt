package com.autonoumus.ai.di

import com.autonoumus.ai.data.repository.HomeRepositoryImpl
import com.autonoumus.ai.data.repository.MediaCaptureRepositoryImpl
import com.autonoumus.ai.data.repository.OnboardingRepositoryImpl
import com.autonoumus.ai.data.repository.SplashRepositoryImpl
import com.autonoumus.ai.data.repository.StampSettingsRepositoryImpl
import com.autonoumus.ai.domain.repository.HomeRepository
import com.autonoumus.ai.domain.repository.MediaCaptureRepository
import com.autonoumus.ai.domain.repository.OnboardingRepository
import com.autonoumus.ai.domain.repository.SplashRepository
import com.autonoumus.ai.domain.repository.StampSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSplashRepository(
        splashRepositoryImpl: SplashRepositoryImpl
    ): SplashRepository

    @Binds
    @Singleton
    abstract fun bindOnboardingRepository(
        onboardingRepositoryImpl: OnboardingRepositoryImpl
    ): OnboardingRepository

    @Binds
    @Singleton
    abstract fun bindHomeRepository(
        homeRepositoryImpl: HomeRepositoryImpl
    ): HomeRepository

    @Binds
    @Singleton
    abstract fun bindStampSettingsRepository(
        stampSettingsRepositoryImpl: StampSettingsRepositoryImpl
    ): StampSettingsRepository

    @Binds
    @Singleton
    abstract fun bindMediaCaptureRepository(
        mediaCaptureRepositoryImpl: MediaCaptureRepositoryImpl
    ): MediaCaptureRepository

    @Binds
    @Singleton
    abstract fun bindTripHistoryRepository(
        tripHistoryRepositoryImpl: com.autonoumus.ai.data.repository.TripHistoryRepositoryImpl
    ): com.autonoumus.ai.domain.repository.TripHistoryRepository

    @Binds
    @Singleton
    abstract fun bindNearbyPlacesRepository(
        nearbyPlacesRepositoryImpl: com.autonoumus.ai.data.repository.NearbyPlacesRepositoryImpl
    ): com.autonoumus.ai.domain.repository.NearbyPlacesRepository

    @Binds
    @Singleton
    abstract fun bindStreetCameraRepository(
        streetCameraRepositoryImpl: com.autonoumus.ai.data.repository.StreetCameraRepositoryImpl
    ): com.autonoumus.ai.domain.repository.StreetCameraRepository

    @Binds
    @Singleton
    abstract fun bindSavedLocationTemplateRepository(
        savedLocationTemplateRepositoryImpl: com.autonoumus.ai.data.repository.SavedLocationTemplateRepositoryImpl
    ): com.autonoumus.ai.domain.repository.SavedLocationTemplateRepository
}
