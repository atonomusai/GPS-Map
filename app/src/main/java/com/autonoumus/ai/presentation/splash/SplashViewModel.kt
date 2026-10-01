package com.autonoumus.ai.presentation.splash

import androidx.lifecycle.viewModelScope
import com.autonoumus.ai.core.base.BaseViewModel
import com.autonoumus.ai.domain.usecase.InitializeAppUseCase
import com.autonoumus.ai.domain.usecase.IsOnboardingCompletedUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val initializeAppUseCase: InitializeAppUseCase,
    private val isOnboardingCompletedUseCase: IsOnboardingCompletedUseCase
) : BaseViewModel() {

    private val _uiState = MutableStateFlow<SplashUiState>(SplashUiState.Loading(progress = 0))
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        startInitialization()
    }

    fun isOnboardingCompleted(): Boolean {
        return isOnboardingCompletedUseCase()
    }

    private fun startInitialization() {
        viewModelScope.launch {
            initializeAppUseCase()
                .catch {
                    // Fallback gracefully to ready state on unexpected error
                    _uiState.value = SplashUiState.Ready
                }
                .collect { progress ->
                    if (progress >= 100) {
                        _uiState.value = SplashUiState.Loading(100)
                        _uiState.value = SplashUiState.Ready
                    } else {
                        _uiState.value = SplashUiState.Loading(progress)
                    }
                }
        }
    }
}
