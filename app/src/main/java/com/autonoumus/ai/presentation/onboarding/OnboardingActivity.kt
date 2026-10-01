package com.autonoumus.ai.presentation.onboarding

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.databinding.ActivityOnboardingBinding
import com.autonoumus.ai.presentation.main.MainActivity
import com.autonoumus.ai.presentation.onboarding.adapter.OnboardingPagerAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class OnboardingActivity : BaseActivity<ActivityOnboardingBinding>() {

    private val viewModel: OnboardingViewModel by viewModels()
    private var adapter: OnboardingPagerAdapter? = null

    override fun inflateBinding(inflater: LayoutInflater): ActivityOnboardingBinding {
        return ActivityOnboardingBinding.inflate(inflater)
    }

    override fun initViews() {
        binding.btnNext.setOnClickListener {
            viewModel.onNextClicked()
        }

        binding.btnSkip.setOnClickListener {
            viewModel.onSkipClicked()
        }

        binding.viewPagerOnboarding.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                viewModel.onPageChanged(position)
            }
        })
    }

    override fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderUiState(state)
                    }
                }
                launch {
                    viewModel.navigationEvents.collect { event ->
                        handleNavigationEvent(event)
                    }
                }
            }
        }
    }

    private fun renderUiState(state: OnboardingUiState) {
        if (adapter == null && state.items.isNotEmpty()) {
            adapter = OnboardingPagerAdapter(state.items)
            binding.viewPagerOnboarding.adapter = adapter
            binding.dotsIndicator.attachToViewPager(binding.viewPagerOnboarding)
        }

        // Header Title (Slide 1 shows "Features", or stays clean)
      //  binding.tvHeaderTitle.text =  "Features"

        // Update progress button animation
        binding.btnNext.setProgress(state.progressPercent, state.isLastPage)

        // No point skipping when Next already goes to Home on the last slide
        binding.btnSkip.visibility = if (state.isLastPage) View.GONE else View.VISIBLE
    }

    private fun handleNavigationEvent(event: OnboardingNavigationEvent) {
        when (event) {
            is OnboardingNavigationEvent.ScrollToPage -> {
                binding.viewPagerOnboarding.setCurrentItem(event.page, true)
            }
            is OnboardingNavigationEvent.NavigateToMain -> {
                navigateToMain()
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
