package com.autonoumus.ai.presentation.splash

sealed interface SplashUiState {
    data class Loading(val progress: Int) : SplashUiState
    data object Ready : SplashUiState
}
