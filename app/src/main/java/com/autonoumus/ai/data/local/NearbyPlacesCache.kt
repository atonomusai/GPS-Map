package com.autonoumus.ai.data.local

import android.content.Context
import android.location.Location
import android.util.Log
import com.autonoumus.ai.core.util.OpeningHoursParser
import com.autonoumus.ai.domain.model.NearbyCategory
import com.autonoumus.ai.domain.model.NearbyPlace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-level cache (memory + disk) for Nearby Places results, so returning to the screen - even
 * after the app was closed - shows the places immediately instead of waiting 3-30s for the
 * public Overpass servers again.
 *
 * One entry = the places of one category fetched around one centre with one radius. A request is
 * served from an entry of the same category whose centre is close enough and whose radius covers
 * it; distances and "open now" are recomputed for the request, since the user may have moved and
 * the time of day has changed.
 *
 * Entry metadata lives in the file name, so finding a match never has to parse any JSON; only
 * the chosen entry is read (and then kept in a small in-memory LRU).
 */
@Singleton
class NearbyPlacesCache @Inject constructor(
    @ApplicationContext context: Context
) {

    class Hit(val places: List<NearbyPlace>, val fetchedAtMs: Long) {
        val ageMs: Long get() = System.currentTimeMillis() - fetchedAtMs
    }

    private class Meta(
        val file: File,
        val category: NearbyCategory,
        val lat: Double,
        val lon: Double,
        val radius: Int,
        /** false when Overpass hit the result limit: then it's an arbitrary subset, not every place in the circle. */
        val complete: Boolean,
        val fetchedAtMs: Long
    )

    private val dir = File(context.cacheDir, "nearby_places")
    private val mutex = Mutex()
    private var index: MutableList<Meta>? = null
    private val parsed = object : LinkedHashMap<String, List<NearbyPlace>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<NearbyPlace>>?) =
            size > MEMORY_ENTRIES
    }

    suspend fun find(
        lat: Double,
        lon: Double,
        category: NearbyCategory,
        radiusMeters: Int
    ): Hit? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val tolerance = maxOf(MIN_CENTER_TOLERANCE_M, radiusMeters * CENTER_TOLERANCE_FRACTION)
            val dist = FloatArray(1)
            val match = loadIndex()
                .filter { meta ->
                    if (meta.category != category || now - meta.fetchedAtMs > MAX_AGE_MS) return@filter false
                    // Same radius, or a larger radius that really contains every place inside it
                    if (meta.radius < radiusMeters) return@filter false
                    if (meta.radius > radiusMeters && !meta.complete) return@filter false
                    Location.distanceBetween(lat, lon, meta.lat, meta.lon, dist)
                    dist[0] <= tolerance
                }
                .maxByOrNull { it.fetchedAtMs } ?: return@withLock null

            val places = readPlaces(match) ?: return@withLock null
            Hit(adaptTo(places, lat, lon, radiusMeters), match.fetchedAtMs)
        }
    }

    suspend fun put(
        lat: Double,
        lon: Double,
        category: NearbyCategory,
        radiusMeters: Int,
        complete: Boolean,
        places: List<NearbyPlace>
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val list = loadIndex()
                val now = System.currentTimeMillis()
                val tolerance = maxOf(MIN_CENTER_TOLERANCE_M, radiusMeters * CENTER_TOLERANCE_FRACTION)
                val dist = FloatArray(1)

                // The new entry supersedes older ones for the same area that it fully covers
                list.filter { old ->
                    old.category == category && old.radius <= radiusMeters && run {
                        Location.distanceBetween(lat, lon, old.lat, old.lon, dist)
                        dist[0] <= tolerance
                    }
                }.forEach { remove(it) }

                dir.mkdirs()
                val name = listOf(category.name, lat, lon, radiusMeters, if (complete) 1 else 0, now)
                    .joinToString(SEP) + ".json"
                val file = File(dir, name)
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(serialize(places).toString())
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return@withLock
                }
                list += Meta(file, category, lat, lon, radiusMeters, complete, now)
                parsed[file.name] = places

                // Bound disk usage: drop expired entries, then the oldest beyond the limit
                list.filter { now - it.fetchedAtMs > MAX_AGE_MS }.forEach { remove(it) }
                while (list.size > MAX_DISK_ENTRIES) {
                    remove(list.minBy { it.fetchedAtMs })
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not write nearby cache: ${e.message}")
            }
        }
    }

    private fun remove(meta: Meta) {
        index?.remove(meta)
        parsed.remove(meta.file.name)
        meta.file.delete()
    }

    private fun loadIndex(): MutableList<Meta> {
        index?.let { return it }
        val metas = dir.listFiles()
            ?.mapNotNull { file ->
                if (file.name.endsWith(".tmp")) {
                    file.delete()
                    return@mapNotNull null
                }
                parseMeta(file) ?: run { file.delete(); null }
            }
            ?.toMutableList()
            ?: mutableListOf()
        index = metas
        return metas
    }

    private fun parseMeta(file: File): Meta? {
        val parts = file.name.removeSuffix(".json").split(SEP)
        if (parts.size != 6) return null
        return try {
            Meta(
                file = file,
                category = NearbyCategory.valueOf(parts[0]),
                lat = parts[1].toDouble(),
                lon = parts[2].toDouble(),
                radius = parts[3].toInt(),
                complete = parts[4] == "1",
                fetchedAtMs = parts[5].toLong()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun readPlaces(meta: Meta): List<NearbyPlace>? {
        parsed[meta.file.name]?.let { return it }
        return try {
            deserialize(JSONArray(meta.file.readText())).also { parsed[meta.file.name] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unreadable nearby cache entry: ${e.message}")
            remove(meta)
            null
        }
    }

    /** Distances relative to the requested centre, only places inside the radius, fresh "open now". */
    private fun adaptTo(places: List<NearbyPlace>, lat: Double, lon: Double, radiusMeters: Int): List<NearbyPlace> {
        val dist = FloatArray(1)
        return places.mapNotNull { place ->
            Location.distanceBetween(lat, lon, place.latitude, place.longitude, dist)
            val d = dist[0].toDouble()
            if (d > radiusMeters) null
            else place.copy(distanceMeters = d, isOpen = OpeningHoursParser.isOpenNow(place.openingHours))
        }.sortedBy { it.distanceMeters }
    }

    private fun serialize(places: List<NearbyPlace>): JSONArray {
        val array = JSONArray()
        for (p in places) {
            val tags = JSONObject()
            p.tags.forEach { (k, v) -> tags.put(k, v) }
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("categoryName", p.categoryName)
                    .put("categoryType", p.categoryType.name)
                    .put("lat", p.latitude)
                    .put("lon", p.longitude)
                    .put("address", p.address)
                    .put("street", p.street)
                    .put("city", p.city)
                    .putOpt("phone", p.phone)
                    .putOpt("website", p.website)
                    .putOpt("openingHours", p.openingHours)
                    .putOpt("imageUrl", p.imageUrl)
                    .put("rating", p.rating.toDouble())
                    .put("reviewCount", p.reviewCount)
                    .put("tags", tags)
            )
        }
        return array
    }

    private fun deserialize(array: JSONArray): List<NearbyPlace> {
        val result = ArrayList<NearbyPlace>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val tagsJson = o.optJSONObject("tags")
            val tags = mutableMapOf<String, String>()
            tagsJson?.keys()?.forEach { k -> tags[k] = tagsJson.optString(k) }
            result += NearbyPlace(
                id = o.getString("id"),
                name = o.getString("name"),
                categoryName = o.optString("categoryName"),
                categoryType = runCatching { NearbyCategory.valueOf(o.optString("categoryType")) }
                    .getOrDefault(NearbyCategory.ALL),
                latitude = o.getDouble("lat"),
                longitude = o.getDouble("lon"),
                distanceMeters = 0.0,
                address = o.optString("address"),
                street = o.optString("street"),
                city = o.optString("city"),
                phone = o.optNullableString("phone"),
                website = o.optNullableString("website"),
                openingHours = o.optNullableString("openingHours"),
                imageUrl = o.optNullableString("imageUrl"),
                rating = o.optDouble("rating", 4.5).toFloat(),
                reviewCount = o.optInt("reviewCount", 85),
                tags = tags
            )
        }
        return result
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private companion object {
        const val TAG = "NearbyPlacesCache"
        const val SEP = "~"
        const val MEMORY_ENTRIES = 6
        const val MAX_DISK_ENTRIES = 40
        /** Entries older than this are ignored and deleted. Fresher-but-old entries are shown and refreshed in the background. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        /** A user who moved a little still gets the cached list (the background refresh then re-centres it). */
        const val MIN_CENTER_TOLERANCE_M = 250f
        const val CENTER_TOLERANCE_FRACTION = 0.1f
    }
}
