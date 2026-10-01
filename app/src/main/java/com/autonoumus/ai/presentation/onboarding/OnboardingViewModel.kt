package com.autonoumus.ai.presentation.onboarding

import androidx.lifecycle.viewModelScope
import com.autonoumus.ai.core.base.BaseViewModel
import com.autonoumus.ai.domain.usecase.GetOnboardingItemsUseCase
import com.autonoumus.ai.domain.usecase.SetOnboardingCompletedUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface OnboardingNavigationEvent {
    data class ScrollToPage(val page: Int) : OnboardingNavigationEvent
    data object NavigateToMain : OnboardingNavigationEvent
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val getOnboardingItemsUseCase: GetOnboardingItemsUseCase,
    private val setOnboardingCompletedUseCase: SetOnboardingCompletedUseCase
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<OnboardingNavigationEvent>()
    val navigationEvents: SharedFlow<OnboardingNavigationEvent> = _navigationEvents.asSharedFlow()

    init {
        loadOnboardingItems()
    }

    private fun loadOnboardingItems() {
        val items = getOnboardingItemsUseCase()
        val total = items.size.coerceAtLeast(1)
        _uiState.update {
            it.copy(
                items = items,
                currentPage = 0,
                progressPercent = (1f / total) * 100f,
                isLastPage = items.size <= 1
            )
        }
    }

    fun onPageChanged(page: Int) {
        val items = _uiState.value.items
        val total = items.size.coerceAtLeast(1)
        val progress = ((page + 1).toFloat() / total) * 100f
        val isLast = page >= items.size - 1

        _uiState.update {
            it.copy(
                currentPage = page,
                progressPercent = progress,
                isLastPage = isLast
            )
        }
    }

    fun onNextClicked() {
        val current = _uiState.value.currentPage
        val total = _uiState.value.items.size

        if (current < total - 1) {
            viewModelScope.launch {
                _navigationEvents.emit(OnboardingNavigationEvent.ScrollToPage(current + 1))
            }
        } else {
            completeOnboarding()
        }
    }

    fun onSkipClicked() {
        completeOnboarding()
    }

    private fun completeOnboarding() {
        setOnboardingCompletedUseCase(true)
        viewModelScope.launch {
            _navigationEvents.emit(OnboardingNavigationEvent.NavigateToMain)
        }
    }
}
