package com.autonoumus.ai.core.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale

data class OsmSearchResult(
    val placeName: String,
    val fullAddress: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val latitudeFormatted: String,
    val longitudeFormatted: String
)

data class OsmReverseGeoResult(
    val primaryTitle: String,
    val fullAddress: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val latitudeFormatted: String,
    val longitudeFormatted: String
)

object OsmGeocodingHelper {

    private val USER_AGENT get() = AppLinks.userAgent
    private const val NOMINATIM_SEARCH_URL = "https://nominatim.openstreetmap.org/search"
    private const val NOMINATIM_REVERSE_URL = "https://nominatim.openstreetmap.org/reverse"

    // Thread-safe LRU Cache for reverse geocoding to make map dragging instantaneous
    private val reverseGeoCache: MutableMap<String, OsmReverseGeoResult> = Collections.synchronizedMap(
        object : LinkedHashMap<String, OsmReverseGeoResult>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OsmReverseGeoResult>?): Boolean {
                return size > 120
            }
        }
    )

    fun formatLatitude(lat: Double, decimals: Int = 4): String {
        val direction = if (lat >= 0) "N" else "S"
        return String.format(Locale.US, "%.${decimals}f° %s", Math.abs(lat), direction)
    }

    fun formatLongitude(lon: Double, decimals: Int = 4): String {
        val direction = if (lon >= 0) "E" else "W"
        return String.format(Locale.US, "%.${decimals}f° %s", Math.abs(lon), direction)
    }

    suspend fun searchLocations(
        query: String,
        context: Context,
        userLat: Double? = null,
        userLon: Double? = null,
        radiusMeters: Int? = null,
        bounded: Boolean = false
    ): List<OsmSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<OsmSearchResult>()

        // 1. Try Android Native Geocoder first (Google Play Services powered, sub-50ms latency)
        if (Geocoder.isPresent()) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocationName(trimmed, 8)
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(trimmed, 8)
                }

                addresses?.forEach { addr ->
                    val place = addr.featureName ?: addr.locality ?: addr.adminArea ?: trimmed
                    val full = (0..addr.maxAddressLineIndex).mapNotNull { addr.getAddressLine(it) }.joinToString(", ")
                    val country = addr.countryName ?: ""
                    results.add(
                        OsmSearchResult(
                            placeName = place,
                            fullAddress = full.ifEmpty { "$place, $country" },
                            country = country,
                            latitude = addr.latitude,
                            longitude = addr.longitude,
                            latitudeFormatted = formatLatitude(addr.latitude),
                            longitudeFormatted = formatLongitude(addr.longitude)
                        )
                    )
                }
            } catch (e: Exception) {
                // Ignore and proceed to Nominatim
            }
        }

        // 2. Query OSM Nominatim if native geocoder returned fewer results or failed
        if (results.size < 3) {
            try {
                val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
                val viewboxParam = if (userLat != null && userLon != null && userLat != 0.0 && userLon != 0.0) {
                    val delta = if (radiusMeters != null && radiusMeters > 0) {
                        (radiusMeters.toDouble() / 111320.0).coerceAtLeast(0.008)
                    } else {
                        0.5 // ~55 km radius bias
                    }
                    val boundedParam = if (bounded) "&bounded=1" else "&bounded=0"
                    "&viewbox=${userLon - delta},${userLat + delta},${userLon + delta},${userLat - delta}$boundedParam"
                } else ""

                val urlString = "$NOMINATIM_SEARCH_URL?q=$encodedQuery&format=json&addressdetails=1&limit=15$viewboxParam"
                val url = URL(urlString)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept-Language", Locale.getDefault().language)
                    connectTimeout = 2500
                    readTimeout = 2500
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                    val jsonArray = JSONArray(responseText)

                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val lat = item.optDouble("lat", 0.0)
                        val lon = item.optDouble("lon", 0.0)
                        val displayName = item.optString("display_name", "")
                        val addressObj = item.optJSONObject("address")

                        val country = addressObj?.optString("country", "") ?: ""
                        val city = addressObj?.let {
                            when {
                                it.has("city") -> it.getString("city")
                                it.has("town") -> it.getString("town")
                                it.has("village") -> it.getString("village")
                                it.has("municipality") -> it.getString("municipality")
                                it.has("county") -> it.getString("county")
                                it.has("state") -> it.getString("state")
                                else -> ""
                            }
                        }.orEmpty()

                        val name = item.optString("name", "").ifEmpty {
                            if (city.isNotEmpty()) city else displayName.split(",").firstOrNull()?.trim() ?: trimmed
                        }

                        if (lat != 0.0 || lon != 0.0) {
                            results.add(
                                OsmSearchResult(
                                    placeName = name,
                                    fullAddress = displayName,
                                    country = country.ifEmpty { displayName.split(",").lastOrNull()?.trim() ?: "" },
                                    latitude = lat,
                                    longitude = lon,
                                    latitudeFormatted = formatLatitude(lat),
                                    longitudeFormatted = formatLongitude(lon)
                                )
                            )
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                // Network error
            }
        }

        // 3. Fallback to built-in famous cities / landmark dataset if still empty
        if (results.isEmpty() && !bounded) {
            val localMatches = WorldLocationProvider.searchCities(trimmed)
            localMatches.forEach { local ->
                results.add(
                    OsmSearchResult(
                        placeName = local.primaryTitle,
                        fullAddress = local.address,
                        country = local.country,
                        latitude = local.rawLatitude,
                        longitude = local.rawLongitude,
                        latitudeFormatted = local.latitudeFormatted,
                        longitudeFormatted = local.longitudeFormatted
                    )
                )
            }
        }

        var distinctResults = results.distinctBy { "${it.latitude},${it.longitude}" }

        // Filter and sort by proximity to user location if available
        if (userLat != null && userLon != null && userLat != 0.0 && userLon != 0.0) {
            val distArr = FloatArray(1)
            if (bounded && radiusMeters != null && radiusMeters > 0) {
                distinctResults = distinctResults.filter { res ->
                    android.location.Location.distanceBetween(userLat, userLon, res.latitude, res.longitude, distArr)
                    distArr[0] <= radiusMeters * 1.25f
                }
            }
            distinctResults.sortedBy { res ->
                android.location.Location.distanceBetween(userLat, userLon, res.latitude, res.longitude, distArr)
                distArr[0]
            }
        } else {
            distinctResults
        }
    }

    suspend fun reverseGeocode(lat: Double, lon: Double, context: Context): OsmReverseGeoResult = withContext(Dispatchers.IO) {
        val latFmt = formatLatitude(lat)
        val lonFmt = formatLongitude(lon)
        val cacheKey = String.format(Locale.US, "%.4f,%.4f", lat, lon)

        // Return immediately from fast in-memory LRU cache
        reverseGeoCache[cacheKey]?.let { cached ->
            return@withContext cached
        }

        // 1. Try Android Native Geocoder first (Google Play Services / Network backed: ~15-40ms)
        if (Geocoder.isPresent()) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses: List<Address>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocation(lat, lon, 1)
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lon, 1)
                }

                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val locality = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: "Selected Location"
                    val country = addr.countryName ?: ""
                    val street = addr.getAddressLine(0) ?: addr.thoroughfare ?: "$locality, $country"

                    val res = OsmReverseGeoResult(
                        primaryTitle = locality,
                        fullAddress = street,
                        country = country,
                        latitude = lat,
                        longitude = lon,
                        latitudeFormatted = latFmt,
                        longitudeFormatted = lonFmt
                    )
                    reverseGeoCache[cacheKey] = res
                    return@withContext res
                }
            } catch (e: Exception) {
                // Fall through to Nominatim
            }
        }

        // 2. Try OSM Nominatim reverse geocoding fallback
        try {
            val urlString = "$NOMINATIM_REVERSE_URL?lat=$lat&lon=$lon&format=json&addressdetails=1"
            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Language", Locale.getDefault().language)
                connectTimeout = 2500
                readTimeout = 2500
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(responseText)
                val displayName = json.optString("display_name", "")
                val addressObj = json.optJSONObject("address")

                val country = addressObj?.optString("country", "") ?: ""
                val city = addressObj?.let {
                    when {
                        it.has("city") -> it.getString("city")
                        it.has("town") -> it.getString("town")
                        it.has("village") -> it.getString("village")
                        it.has("suburb") -> it.getString("suburb")
                        it.has("county") -> it.getString("county")
                        it.has("state") -> it.getString("state")
                        else -> ""
                    }
                }.orEmpty()

                val road = addressObj?.optString("road", "") ?: ""
                val primary = when {
                    road.isNotEmpty() && city.isNotEmpty() -> "$road, $city"
                    city.isNotEmpty() -> city
                    else -> displayName.split(",").firstOrNull()?.trim() ?: "Selected Location"
                }

                if (displayName.isNotBlank()) {
                    connection.disconnect()
                    val res = OsmReverseGeoResult(
                        primaryTitle = primary,
                        fullAddress = displayName,
                        country = country.ifEmpty { displayName.split(",").lastOrNull()?.trim() ?: "Global" },
                        latitude = lat,
                        longitude = lon,
                        latitudeFormatted = latFmt,
                        longitudeFormatted = lonFmt
                    )
                    reverseGeoCache[cacheKey] = res
                    return@withContext res
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            // Fall through to coordinates
        }

        // 3. Fallback to coordinates
        val fallback = OsmReverseGeoResult(
            primaryTitle = "Location: $latFmt, $lonFmt",
            fullAddress = "Coordinates: $latFmt, $lonFmt",
            country = "Global",
            latitude = lat,
            longitude = lon,
            latitudeFormatted = latFmt,
            longitudeFormatted = lonFmt
        )
        reverseGeoCache[cacheKey] = fallback
        fallback
    }
}
