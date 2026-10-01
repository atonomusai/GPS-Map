package com.autonoumus.ai.data.local

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferenceManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "gsm_camera_prefs"
        private const val KEY_ONBOARDING_COMPLETED = "key_onboarding_completed"
        private const val KEY_SHOWN_OEM_BATTERY_HINT = "key_shown_oem_battery_hint"
        private const val KEY_DISTANCE_UNIT_KM = "key_distance_unit_km"
        private const val KEY_SPEED_UNIT_KMH = "key_speed_unit_kmh"
        private const val KEY_ALTITUDE_UNIT = "key_altitude_unit"
        private const val KEY_COMPASS_THEME = "key_compass_theme"

        // Stamp content/typography settings
        private const val KEY_STAMP_TEMPLATE = "key_stamp_template"
        private const val KEY_STAMP_SHOW_TITLE = "key_stamp_show_title"
        private const val KEY_STAMP_SHOW_MAP = "key_stamp_show_map"
        private const val KEY_MAP_TYPE = "key_map_type"
        private const val KEY_STAMP_SHOW_ADDRESS = "key_stamp_show_address"
        private const val KEY_STAMP_SHOW_TIMEZONE = "key_stamp_show_timezone"
        private const val KEY_STAMP_SHOW_LOCATION = "key_stamp_show_location"
        private const val KEY_STAMP_OPACITY = "key_stamp_opacity"
        private const val KEY_STAMP_FONT_SIZE = "key_stamp_font_size"
        private const val KEY_STAMP_FONT_STYLE = "key_stamp_font_style"
        private const val KEY_STAMP_TEXT_COLOR = "key_stamp_text_color"
        private const val KEY_STAMP_POSITION = "key_stamp_position"
        private const val KEY_STAMP_TEXT_ALIGNMENT = "key_stamp_text_alignment"

        // Camera capture settings
        private const val KEY_CAMERA_VIDEO_QUALITY = "key_camera_video_quality"
        private const val KEY_CAMERA_FRAME_RATE = "key_camera_frame_rate"
        private const val KEY_CAMERA_PHOTO_RESOLUTION = "key_camera_photo_resolution"
        private const val KEY_CAMERA_FLASH_MODE = "key_camera_flash_mode"
        private const val KEY_CAMERA_LENS_FACING = "key_camera_lens_facing"
        private const val KEY_HAS_RATED_APP = "key_has_rated_app"
        private const val KEY_HAS_REQUESTED_LOCATION = "key_has_requested_location"
        private const val KEY_LAST_LANDMARK_NAME = "key_last_landmark_name"
        private const val KEY_LAST_LANDMARK_IMAGE_URL = "key_last_landmark_image_url"
    }

    var lastLandmarkName: String?
        get() = prefs.getString(KEY_LAST_LANDMARK_NAME, null)
        set(value) = prefs.edit().putString(KEY_LAST_LANDMARK_NAME, value).apply()

    var lastLandmarkImageUrl: String?
        get() = prefs.getString(KEY_LAST_LANDMARK_IMAGE_URL, null)
        set(value) = prefs.edit().putString(KEY_LAST_LANDMARK_IMAGE_URL, value).apply()

    var hasRequestedLocationPermission: Boolean
        get() = prefs.getBoolean(KEY_HAS_REQUESTED_LOCATION, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_REQUESTED_LOCATION, value).apply()

    /** User accepted the trip-tracking location disclosure (required by Play before tracking). */
    var hasAcceptedTripLocationDisclosure: Boolean
        get() = prefs.getBoolean("key_trip_location_disclosure_accepted", false)
        set(value) = prefs.edit().putBoolean("key_trip_location_disclosure_accepted", value).apply()

    /** One-time hint asking Xiaomi/Redmi/POCO users to lift MIUI battery restrictions for tracking. */
    var hasShownOemBatteryHint: Boolean
        get() = prefs.getBoolean(KEY_SHOWN_OEM_BATTERY_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOWN_OEM_BATTERY_HINT, value).apply()

    var hasRatedApp: Boolean
        get() = prefs.getBoolean(KEY_HAS_RATED_APP, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_RATED_APP, value).apply()

    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    var isDistanceUnitKm: Boolean
        get() = prefs.getBoolean(KEY_DISTANCE_UNIT_KM, true)
        set(value) = prefs.edit().putBoolean(KEY_DISTANCE_UNIT_KM, value).apply()

    var isSpeedUnitKmh: Boolean
        get() = prefs.getBoolean(KEY_SPEED_UNIT_KMH, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_UNIT_KMH, value).apply()

    var altitudeUnit: String
        get() = prefs.getString(KEY_ALTITUDE_UNIT, "m") ?: "m"
        set(value) = prefs.edit().putString(KEY_ALTITUDE_UNIT, value).apply()

    var compassTheme: String
        get() = prefs.getString(KEY_COMPASS_THEME, "NAUTICAL") ?: "NAUTICAL"
        set(value) = prefs.edit().putString(KEY_COMPASS_THEME, value).apply()

    // --- Stamp settings ---

    var stampTemplate: String
        get() = prefs.getString(KEY_STAMP_TEMPLATE, "MAP_GLASS_CARD") ?: "MAP_GLASS_CARD"
        set(value) = prefs.edit().putString(KEY_STAMP_TEMPLATE, value).apply()

    var stampShowTitleHeader: Boolean
        get() = prefs.getBoolean(KEY_STAMP_SHOW_TITLE, true)
        set(value) = prefs.edit().putBoolean(KEY_STAMP_SHOW_TITLE, value).apply()

    var stampShowMapBadge: Boolean
        get() = prefs.getBoolean(KEY_STAMP_SHOW_MAP, true)
        set(value) = prefs.edit().putBoolean(KEY_STAMP_SHOW_MAP, value).apply()

    var mapType: String
        get() = prefs.getString(KEY_MAP_TYPE, "HYBRID") ?: "HYBRID"
        set(value) = prefs.edit().putString(KEY_MAP_TYPE, value).apply()

    var stampShowAddress: Boolean
        get() = prefs.getBoolean(KEY_STAMP_SHOW_ADDRESS, false)
        set(value) = prefs.edit().putBoolean(KEY_STAMP_SHOW_ADDRESS, value).apply()

    var stampShowTimeZone: Boolean
        get() = prefs.getBoolean(KEY_STAMP_SHOW_TIMEZONE, false)
        set(value) = prefs.edit().putBoolean(KEY_STAMP_SHOW_TIMEZONE, value).apply()

    var stampShowCoordinates: Boolean
        get() = prefs.getBoolean(KEY_STAMP_SHOW_LOCATION, true)
        set(value) = prefs.edit().putBoolean(KEY_STAMP_SHOW_LOCATION, value).apply()

    var stampOpacityPercent: Int
        get() = prefs.getInt(KEY_STAMP_OPACITY, 50)
        set(value) = prefs.edit().putInt(KEY_STAMP_OPACITY, value).apply()

    var stampFontSizePercent: Int
        get() = prefs.getInt(KEY_STAMP_FONT_SIZE, 100)
        set(value) = prefs.edit().putInt(KEY_STAMP_FONT_SIZE, value).apply()

    var stampFontStyle: String
        get() = prefs.getString(KEY_STAMP_FONT_STYLE, "BOLD") ?: "BOLD"
        set(value) = prefs.edit().putString(KEY_STAMP_FONT_STYLE, value).apply()

    var stampTextColor: Int
        get() = prefs.getInt(KEY_STAMP_TEXT_COLOR, Color.WHITE)
        set(value) = prefs.edit().putInt(KEY_STAMP_TEXT_COLOR, value).apply()

    var stampPosition: String
        get() = prefs.getString(KEY_STAMP_POSITION, "BOTTOM_LEFT") ?: "BOTTOM_LEFT"
        set(value) = prefs.edit().putString(KEY_STAMP_POSITION, value).apply()

    var stampTextAlignment: String
        get() = prefs.getString(KEY_STAMP_TEXT_ALIGNMENT, "LEFT") ?: "LEFT"
        set(value) = prefs.edit().putString(KEY_STAMP_TEXT_ALIGNMENT, value).apply()

    // --- Camera settings ---

    /** Name of the last-selected androidx.camera.video.Quality, or null if never chosen (use device default = highest). */
    var cameraVideoQualityName: String?
        get() = prefs.getString(KEY_CAMERA_VIDEO_QUALITY, null)
        set(value) = prefs.edit().putString(KEY_CAMERA_VIDEO_QUALITY, value).apply()

    /** Fixed target fps, or 0 if never chosen (use device default AE range). */
    var cameraFrameRateFps: Int
        get() = prefs.getInt(KEY_CAMERA_FRAME_RATE, 0)
        set(value) = prefs.edit().putInt(KEY_CAMERA_FRAME_RATE, value).apply()

    /** "<width>x<height>" of the last-selected photo resolution, or null if never chosen (use device default = highest). */
    var cameraPhotoResolution: String?
        get() = prefs.getString(KEY_CAMERA_PHOTO_RESOLUTION, null)
        set(value) = prefs.edit().putString(KEY_CAMERA_PHOTO_RESOLUTION, value).apply()

    var cameraFlashMode: String
        get() = prefs.getString(KEY_CAMERA_FLASH_MODE, "OFF") ?: "OFF"
        set(value) = prefs.edit().putString(KEY_CAMERA_FLASH_MODE, value).apply()

    var cameraLensFacing: String
        get() = prefs.getString(KEY_CAMERA_LENS_FACING, "BACK") ?: "BACK"
        set(value) = prefs.edit().putString(KEY_CAMERA_LENS_FACING, value).apply()

    // --- Custom Location Stamp override settings ---

    var isCustomLocationOverrideEnabled: Boolean
        get() = prefs.getBoolean("key_custom_location_override", false)
        set(value) = prefs.edit().putBoolean("key_custom_location_override", value).apply()

    var customLocationCountry: String
        get() = prefs.getString("key_custom_location_country", "") ?: ""
        set(value) = prefs.edit().putString("key_custom_location_country", value).apply()

    var customLocationAddress: String
        get() = prefs.getString("key_custom_location_address", "") ?: ""
        set(value) = prefs.edit().putString("key_custom_location_address", value).apply()

    var customLocationLatitude: String
        get() = prefs.getString("key_custom_location_latitude", "") ?: ""
        set(value) = prefs.edit().putString("key_custom_location_latitude", value).apply()

    var customLocationLongitude: String
        get() = prefs.getString("key_custom_location_longitude", "") ?: ""
        set(value) = prefs.edit().putString("key_custom_location_longitude", value).apply()

    // --- Speedometer Settings ---

    var speedLimit: Int
        get() = prefs.getInt("key_speed_limit_val", 60)
        set(value) = prefs.edit().putInt("key_speed_limit_val", value).apply()

    var isSpeedLimitAlertEnabled: Boolean
        get() = prefs.getBoolean("key_speed_limit_alert_enabled", true)
        set(value) = prefs.edit().putBoolean("key_speed_limit_alert_enabled", value).apply()

    var isVibrationAlertEnabled: Boolean
        get() = prefs.getBoolean("key_speed_vibration_alert_enabled", true)
        set(value) = prefs.edit().putBoolean("key_speed_vibration_alert_enabled", value).apply()

    var isSpeedToleranceBufferEnabled: Boolean
        get() = prefs.getBoolean("key_speed_tolerance_buffer_enabled", false)
        set(value) = prefs.edit().putBoolean("key_speed_tolerance_buffer_enabled", value).apply()

    var speedometerMode: String
        get() = prefs.getString("key_speedometer_display_mode", "ANALOG_GAUGE") ?: "ANALOG_GAUGE"
        set(value) = prefs.edit().putString("key_speedometer_display_mode", value).apply()
}
