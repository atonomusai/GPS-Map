package com.autonoumus.ai.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Once a user permanently denies a permission (taps "Don't allow", or denies twice on most
 * Android versions), calling the system permission launcher again silently does nothing -
 * Android will never show its own dialog again. This is the only way forward: send the user
 * to the app's Settings page themselves. Used by every screen that requests location or
 * storage permission, so denial always leads somewhere instead of a dead end.
 */
object PermissionSettingsDialog {

    fun show(context: Context, message: String, title: String = "Permission Needed") {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton("Open Settings") { dialog, _ ->
                dialog.dismiss()
                openAppSettings(context)
            }
            .setNegativeButton("Not Now") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            // No Settings app to handle this - nothing more we can do
        }
    }

    /**
     * Call this from a permission-result callback once a permission comes back denied.
     * Shows a brief toast rather than popping an intrusive modal dialog immediately.
     * Screens can check for permanent denial on user interactions to offer Settings.
     */
    fun handleDenied(
        context: Context,
        shouldShowRationale: Boolean,
        deniedMessage: String,
        settingsMessage: String = "$deniedMessage\n\nYou can turn this on anytime from Settings.",
        settingsTitle: String = "Permission Needed"
    ) {
        Toast.makeText(context, deniedMessage, Toast.LENGTH_SHORT).show()
    }
}

