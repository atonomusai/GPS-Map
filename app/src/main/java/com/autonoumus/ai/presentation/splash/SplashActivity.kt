package com.autonoumus.ai.presentation.splash

import android.animation.ObjectAnimator
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.databinding.ActivitySplashBinding
import com.autonoumus.ai.presentation.main.MainActivity
import com.autonoumus.ai.presentation.onboarding.OnboardingActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SplashActivity : BaseActivity<ActivitySplashBinding>() {

    private val viewModel: SplashViewModel by viewModels()

    override fun inflateBinding(inflater: LayoutInflater): ActivitySplashBinding {
        return ActivitySplashBinding.inflate(inflater)
    }

    override fun initViews() {
        binding.btnContinue.setOnClickListener {
            navigateNext()
        }
    }

    override fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state)
                }
            }
        }
    }

    private fun renderUiState(state: SplashUiState) {
        when (state) {
            is SplashUiState.Loading -> {
                binding.layoutLoading.visibility = View.VISIBLE
                binding.btnContinue.visibility = View.GONE
                animateProgress(state.progress)
            }
            is SplashUiState.Ready -> {
                if (viewModel.isOnboardingCompleted()) {
                    // Returning user: no Continue button - go straight to Home once the bar is full
                    // (short pause so the completed progress bar is actually seen).
                    animateProgress(100)
                    binding.progressBar.postDelayed({ navigateNext() }, 350)
                } else {
                    showContinueButton()
                }
            }
        }
    }

    private fun animateProgress(targetProgress: Int) {
        ObjectAnimator.ofInt(binding.progressBar, "progress", binding.progressBar.progress, targetProgress).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun showContinueButton() {
        if (binding.btnContinue.visibility == View.VISIBLE) return

        binding.layoutLoading.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                binding.layoutLoading.visibility = View.GONE
                binding.btnContinue.alpha = 0f
                binding.btnContinue.visibility = View.VISIBLE
                binding.btnContinue.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
            .start()
    }

    private var hasNavigated = false

    private fun navigateNext() {
        // Ready can be delivered again (e.g. after returning to STARTED): navigate only once.
        if (hasNavigated || isFinishing || isDestroyed) return
        hasNavigated = true
        val targetActivity = if (viewModel.isOnboardingCompleted()) {
            MainActivity::class.java
        } else {
            OnboardingActivity::class.java
        }
        val intent = Intent(this, targetActivity)
        startActivity(intent)
        finish()
    }
}
