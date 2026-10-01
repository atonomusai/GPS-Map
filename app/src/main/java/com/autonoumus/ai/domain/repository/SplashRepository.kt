package com.autonoumus.ai.domain.repository

import kotlinx.coroutines.flow.Flow

interface SplashRepository {
    fun initializeApp(): Flow<Int>
}
