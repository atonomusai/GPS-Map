package com.autonoumus.ai.presentation.streetcamera

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Color
import androidx.core.view.WindowCompat
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.databinding.ActivityTimelapsePlayerBinding

/**
 * Plays a Windy webcam's day/month/year timelapse (an animated sequence of the camera's own
 * past still frames) inside an embedded web view. This is NOT live video - Windy's Webcams API
 * has no live-video product at any tier - but it's the closest thing to motion the API offers,
 * and is meaningfully more "alive" than a single static frame.
 */
class TimelapsePlayerActivity : BaseActivity<ActivityTimelapsePlayerBinding>() {

    companion object {
        private const val EXTRA_PLAYER_URL = "extra_player_url"
        private const val EXTRA_TITLE = "extra_title"

        fun newIntent(context: Context, playerUrl: String, title: String): Intent {
            return Intent(context, TimelapsePlayerActivity::class.java).apply {
                putExtra(EXTRA_PLAYER_URL, playerUrl)
                putExtra(EXTRA_TITLE, title)
            }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityTimelapsePlayerBinding {
        return ActivityTimelapsePlayerBinding.inflate(inflater)
    }

    override fun initViews() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        binding.btnTimelapseBack.setOnClickListener { finish() }

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.tvTimelapseTitle.text = title.ifBlank { getString(R.string.timelapse_player_title) }

        val playerUrl = intent.getStringExtra(EXTRA_PLAYER_URL)
        if (playerUrl.isNullOrBlank()) {
            showError()
            return
        }

        setupWebView(playerUrl)
    }

    private fun setupWebView(playerUrl: String) {
        binding.webViewTimelapse.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false

            webViewClient = object : WebViewClient() {
                // JavaScript is needed by Windy's player, so keep this WebView on windy.com only;
                // any other link (ads, share, external pages) opens in the user's browser.
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val host = uri.host.orEmpty()
                    if (uri.scheme == "https" && (host == "windy.com" || host.endsWith(".windy.com"))) return false
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                    } catch (e: Exception) {
                        // No app can open it - just stay on the player.
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progressTimelapseLoading.visibility = View.GONE
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        binding.progressTimelapseLoading.visibility = View.GONE
                        showError()
                    }
                }
            }

            loadUrl(playerUrl)
        }
    }

    private fun showError() {
        binding.progressTimelapseLoading.visibility = View.GONE
        binding.webViewTimelapse.visibility = View.GONE
        binding.layoutTimelapseError.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        bindingOrNull?.webViewTimelapse?.apply {
            stopLoading()
            clearHistory()
            destroy()
        }
        super.onDestroy()
    }
}
