package com.autonoumus.ai.data.repository

import com.autonoumus.ai.core.util.DispatcherProvider
import com.autonoumus.ai.domain.repository.SplashRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SplashRepositoryImpl @Inject constructor(
    private val dispatchers: DispatcherProvider
) : SplashRepository {

    override fun initializeApp(): Flow<Int> = flow {
        emit(0)
        val progressSteps = listOf(15, 35, 60, 80, 100)
        for (step in progressSteps) {
            delay(350)
            emit(step)
        }
    }.flowOn(dispatchers.io)
}
