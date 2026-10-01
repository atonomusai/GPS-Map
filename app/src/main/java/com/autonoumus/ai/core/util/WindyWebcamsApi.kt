package com.autonoumus.ai.core.util

import com.autonoumus.ai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class WindyWebcamJson(
    val id: String,
    val title: String,
    val city: String,
    val country: String,
    val countryCode: String,
    val latitude: Double,
    val longitude: Double,
    val thumbnailUrl: String?,
    val previewUrl: String?,
    val playerUrl: String?,
    val categoryIds: List<String>,
    val lastUpdatedEpochMillis: Long
)

/**
 * Thin client for the Windy Webcams API v3 (https://api.windy.com/webcams/docs).
 * Free-tier only: never throws to callers, returns empty/null on any failure so
 * StreetCameraRepositoryImpl can fall back cleanly (missing key, no network, rate limit, etc).
 */
object WindyWebcamsApi {

    private val USER_AGENT get() = AppLinks.userAgent
    private const val BASE_URL = "https://api.windy.com/webcams/api/v3/webcams"
    private const val DEFAULT_NEARBY_RADIUS_KM = 100
    private const val MAX_NEARBY_RADIUS_KM = 100

    private fun hasApiKey(): Boolean = BuildConfig.WINDY_API_KEY.isNotBlank()

    suspend fun getWebcams(
        countryCode: String?,
        categorySlug: String?,
        offset: Int,
        limit: Int,
        nearbyLatitude: Double? = null,
        nearbyLongitude: Double? = null,
        radiusKm: Int? = null
    ): List<WindyWebcamJson> = withContext(Dispatchers.IO) {
        if (!hasApiKey()) return@withContext emptyList()

        try {
            val params = mutableListOf(
                "limit=$limit",
                "offset=$offset",
                "include=categories,location,images,player"
            )
            if (!countryCode.isNullOrBlank()) {
                params += "countries=${URLEncoder.encode(countryCode, "UTF-8")}"
            }
            if (!categorySlug.isNullOrBlank()) {
                params += "categories=${URLEncoder.encode(categorySlug, "UTF-8")}"
            }
            if (nearbyLatitude != null && nearbyLongitude != null) {
                val radius = (radiusKm ?: DEFAULT_NEARBY_RADIUS_KM).coerceIn(1, MAX_NEARBY_RADIUS_KM)
                params += "nearby=${nearbyLatitude},${nearbyLongitude},$radius"
            }

            val urlString = "$BASE_URL?${params.joinToString("&")}"
            val connection = openConnection(urlString)

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(responseText)
                val webcamsArray = json.optJSONArray("webcams") ?: json.optJSONArray("result")
                connection.disconnect()
                return@withContext parseWebcamsArray(webcamsArray)
            }
            connection.disconnect()
        } catch (e: Exception) {
            // Network/parse failure -> caller falls back
        }
        emptyList()
    }

    suspend fun getWebcamDetail(id: String): WindyWebcamJson? = withContext(Dispatchers.IO) {
        if (!hasApiKey() || id.isBlank()) return@withContext null

        try {
            val urlString = "$BASE_URL/${URLEncoder.encode(id, "UTF-8")}?include=categories,location,images,player"
            val connection = openConnection(urlString)

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(responseText)
                connection.disconnect()
                return@withContext parseWebcamObject(json)
            }
            connection.disconnect()
        } catch (e: Exception) {
            // Network/parse failure -> caller falls back
        }
        null
    }

    private fun openConnection(urlString: String): HttpURLConnection {
        return (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("x-windy-api-key", BuildConfig.WINDY_API_KEY)
            connectTimeout = 6000
            readTimeout = 6000
        }
    }

    private fun parseWebcamsArray(array: JSONArray?): List<WindyWebcamJson> {
        if (array == null) return emptyList()
        val results = mutableListOf<WindyWebcamJson>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            results.add(parseWebcamObject(obj))
        }
        return results
    }

    private fun parseWebcamObject(obj: JSONObject): WindyWebcamJson {
        val location = obj.optJSONObject("location")
        val images = obj.optJSONObject("images")
        val current = images?.optJSONObject("current")
        val player = obj.optJSONObject("player")
        val categoriesArray = obj.optJSONArray("categories")

        val categoryIds = mutableListOf<String>()
        if (categoriesArray != null) {
            for (i in 0 until categoriesArray.length()) {
                val catObj = categoriesArray.optJSONObject(i)
                val catId = catObj?.optString("id").orEmpty().ifBlank { categoriesArray.optString(i) }
                if (catId.isNotBlank()) categoryIds.add(catId.lowercase())
            }
        }

        return WindyWebcamJson(
            id = obj.optString("id").ifBlank { obj.optString("webcamId") },
            title = obj.optString("title", "Live Camera"),
            city = location?.optString("city").orEmpty(),
            country = location?.optString("country").orEmpty(),
            countryCode = location?.optString("country_code").orEmpty().uppercase(),
            latitude = location?.optDouble("latitude", 0.0) ?: 0.0,
            longitude = location?.optDouble("longitude", 0.0) ?: 0.0,
            thumbnailUrl = current?.optString("thumbnail").takeUnless { it.isNullOrBlank() },
            previewUrl = current?.optString("preview").takeUnless { it.isNullOrBlank() }
                ?: current?.optString("icon").takeUnless { it.isNullOrBlank() },
            playerUrl = player?.optString("day").takeUnless { it.isNullOrBlank() },
            categoryIds = categoryIds,
            lastUpdatedEpochMillis = parseIsoToEpochMillis(obj.optString("lastUpdatedOn"))
        )
    }

    /** Parses "2026-09-15T18:45:25.000Z" without java.time (minSdk 24, not desugared). */
    private fun parseIsoToEpochMillis(iso: String): Long {
        if (iso.isBlank()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(iso)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
