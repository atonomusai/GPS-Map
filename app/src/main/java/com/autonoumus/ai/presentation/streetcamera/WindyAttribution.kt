package com.autonoumus.ai.presentation.streetcamera

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.core.text.HtmlCompat

/**
 * Windy Webcams API terms (free tier) require the text "Webcams provided by windy.com - add new
 * webcam" with links to windy.com and windy.com/webcams/add wherever webcams are shown.
 */
object WindyAttribution {
    private const val HTML =
        "Webcams provided by <a href=\"https://www.windy.com/\">windy.com</a> - " +
            "<a href=\"https://www.windy.com/webcams/add\">add new webcam</a>"

    fun apply(textView: TextView) {
        textView.text = HtmlCompat.fromHtml(HTML, HtmlCompat.FROM_HTML_MODE_LEGACY)
        textView.movementMethod = LinkMovementMethod.getInstance()
    }
}
