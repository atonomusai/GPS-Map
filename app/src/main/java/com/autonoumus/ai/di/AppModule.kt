package com.autonoumus.ai.di

import com.autonoumus.ai.core.util.DefaultDispatcherProvider
import com.autonoumus.ai.core.util.DispatcherProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(
        dispatcherProvider: DefaultDispatcherProvider
    ): DispatcherProvider
}
