package com.autonoumus.ai.core.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.autonoumus.ai.BuildConfig

/**
 * Contact / legal values used across the app. Set them in local.properties
 * (SUPPORT_EMAIL, PRIVACY_POLICY_URL, TERMS_URL) - the release build fails while they are empty,
 * so the app can't ship with a dead Privacy Policy button or a bouncing support address.
 */
object AppLinks {

    val supportEmail: String = BuildConfig.SUPPORT_EMAIL
    val privacyPolicyUrl: String = BuildConfig.PRIVACY_POLICY_URL
    val termsUrl: String = BuildConfig.TERMS_URL

    /**
     * Identifying User-Agent for OpenStreetMap/Nominatim/Overpass/Wikimedia/EOX requests - their
     * usage policies require the app name plus a working contact.
     */
    val userAgent: String =
        "GSMCAMER_GPS_Camera/${BuildConfig.VERSION_NAME} (Android; ${supportEmail.ifBlank { BuildConfig.APPLICATION_ID }})"

    /** Opens [url] in the browser; shows a message instead of crashing if it can't. */
    fun openUrl(context: Context, url: String) {
        if (url.isBlank()) {
            Toast.makeText(context, "Link not available yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No browser found to open the link", Toast.LENGTH_SHORT).show()
        }
    }
}
