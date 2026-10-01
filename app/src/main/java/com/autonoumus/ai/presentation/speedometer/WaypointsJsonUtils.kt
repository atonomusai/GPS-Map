package com.autonoumus.ai.presentation.speedometer

import org.json.JSONArray

/**
 * Bounds a trip's waypoints JSON to at most [maxPoints] evenly-spaced points before it's
 * passed through an Intent extra - Android's ~1MB Binder transaction limit makes an unbounded
 * JSON blob (from a long trip) a real TransactionTooLargeException crash risk. The full-precision
 * JSON is still what gets persisted to trip history; this bounded version is only for the
 * Activity-to-Activity hand-off.
 */
fun downsampleWaypointsJson(json: String, maxPoints: Int = 500): String {
    if (json.isBlank()) return json
    return try {
        val array = JSONArray(json)
        if (array.length() <= maxPoints) return json
        val step = array.length().toDouble() / maxPoints
        val result = JSONArray()
        for (i in 0 until maxPoints) {
            val index = (i * step).toInt().coerceAtMost(array.length() - 1)
            result.put(array.getJSONObject(index))
        }
        result.toString()
    } catch (e: Exception) {
        json
    }
}
