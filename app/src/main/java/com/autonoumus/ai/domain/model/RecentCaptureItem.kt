package com.autonoumus.ai.domain.model

import android.net.Uri

data class RecentCaptureItem(
    val id: String,
    val contentUri: Uri,
    val dateFormatted: String,
    val locationFormatted: String,
    val isVideo: Boolean = false,
    val timestampMillis: Long = 0L,
    val durationFormatted: String? = null,
    val locationName: String? = null,
    val cityCountry: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)
