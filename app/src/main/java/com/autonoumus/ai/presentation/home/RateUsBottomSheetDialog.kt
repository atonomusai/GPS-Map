package com.autonoumus.ai.presentation.home

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.widget.ImageViewCompat
import com.autonoumus.ai.core.base.BaseBottomSheetDialogFragment
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.BottomSheetRateUsBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class RateUsBottomSheetDialog : BaseBottomSheetDialogFragment<BottomSheetRateUsBinding>() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    var onExitRequested: (() -> Unit)? = null
    var onDismissedOrRated: (() -> Unit)? = null

    private var isFromSettings: Boolean = false
    private var currentRating: Int = 4
    private lateinit var starViews: List<ImageView>

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?): BottomSheetRateUsBinding {
        return BottomSheetRateUsBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isFromSettings = arguments?.getBoolean(ARG_IS_FROM_SETTINGS, false) ?: false

        starViews = listOf(
            binding.ivStar1,
            binding.ivStar2,
            binding.ivStar3,
            binding.ivStar4,
            binding.ivStar5
        )

        setupStarRatingTouch()

        // Default rating 4 stars as shown in design mockup
        setRating(4)

        binding.btnSubmitRate.setOnClickListener {
            handleRatingSubmission()
        }

        if (isFromSettings) {
            binding.btnExitFromRate.text = "Dismiss"
            binding.btnExitFromRate.setOnClickListener {
                dismiss()
                onDismissedOrRated?.invoke()
            }
        } else {
            binding.btnExitFromRate.text = "Exit App"
            binding.btnExitFromRate.setOnClickListener {
                dismiss()
                onExitRequested?.invoke()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupStarRatingTouch() {
        val onTouchListener = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    handleTouchRating(event.rawX)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    handleTouchRating(event.rawX)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    handleTouchRating(event.rawX)
                    view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }

        binding.layoutStars.setOnTouchListener(onTouchListener)
        starViews.forEachIndexed { index, starView ->
            starView.setOnTouchListener(onTouchListener)
            starView.setOnClickListener {
                setRating(index + 1)
            }
        }
    }

    private fun handleTouchRating(rawX: Float) {
        if (starViews.isEmpty()) return

        val location = IntArray(2)
        var selectedRating = 1

        for (i in starViews.indices) {
            val star = starViews[i]
            star.getLocationOnScreen(location)
            val starLeft = location[0].toFloat()

            val threshold = if (i == 0) starLeft else {
                val prevLocation = IntArray(2)
                starViews[i - 1].getLocationOnScreen(prevLocation)
                val prevRight = prevLocation[0].toFloat() + starViews[i - 1].width
                (prevRight + starLeft) / 2f
            }

            if (rawX >= threshold) {
                selectedRating = i + 1
            }
        }

        setRating(selectedRating.coerceIn(1, 5))
    }

    private fun setRating(rating: Int) {
        val prevRating = currentRating
        currentRating = rating

        val activeColor = Color.parseColor("#FFAA00")
        val inactiveColor = Color.parseColor("#64748B")

        starViews.forEachIndexed { index, imageView ->
            val isSelected = index < rating
            ImageViewCompat.setImageTintList(
                imageView,
                ColorStateList.valueOf(if (isSelected) activeColor else inactiveColor)
            )

            // Subtle bounce animation for newly highlighted star
            if (isSelected && index >= prevRating) {
                imageView.animate()
                    .scaleX(1.15f)
                    .scaleY(1.15f)
                    .setDuration(90)
                    .withEndAction {
                        imageView.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(90)
                            .start()
                    }
                    .start()
            } else if (!isSelected) {
                imageView.scaleX = 1.0f
                imageView.scaleY = 1.0f
            }
        }

        if (rating >= 4) {
            binding.layoutFeedbackBox.visibility = View.GONE
            binding.btnSubmitRate.text = "Rate Us"
        } else {
            binding.layoutFeedbackBox.visibility = View.VISIBLE
            binding.btnSubmitRate.text = "Submit"
        }
    }

    private fun handleRatingSubmission() {
        preferenceManager.hasRatedApp = true

        if (currentRating >= 4) {
            openPlayStoreRating()
        } else {
            sendFeedbackViaEmail()
        }
        onDismissedOrRated?.invoke()
        dismiss()
    }

    private fun openPlayStoreRating() {
        val packageName = requireContext().packageName
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        }
        Toast.makeText(requireContext(), "Thank you for your rating!", Toast.LENGTH_SHORT).show()
    }

    private fun sendFeedbackViaEmail() {
        val feedbackText = binding.etFeedback.text.toString().trim()
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(com.autonoumus.ai.core.util.AppLinks.supportEmail))
            putExtra(Intent.EXTRA_SUBJECT, "${getString(com.autonoumus.ai.R.string.app_name)} - User Feedback")
            putExtra(Intent.EXTRA_TEXT, feedbackText.ifBlank { "User rating: $currentRating / 5 stars." })
        }
        try {
            startActivity(Intent.createChooser(emailIntent, "Send Feedback via Email"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Thank you for your feedback!", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val TAG = "RateUsBottomSheetDialog"
        private const val ARG_IS_FROM_SETTINGS = "arg_is_from_settings"

        fun newInstance(isFromSettings: Boolean = false, onExit: (() -> Unit)? = null): RateUsBottomSheetDialog {
            return RateUsBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_FROM_SETTINGS, isFromSettings)
                }
                onExitRequested = onExit
            }
        }
    }
}
