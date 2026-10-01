package com.autonoumus.ai.data.repository

import android.location.Location
import android.util.Log
import com.autonoumus.ai.core.util.OpeningHoursParser
import com.autonoumus.ai.data.local.NearbyPlacesCache
import com.autonoumus.ai.domain.model.CachedNearbyPlaces
import com.autonoumus.ai.domain.model.NearbyCategory
import com.autonoumus.ai.domain.model.NearbyPlace
import com.autonoumus.ai.domain.repository.NearbyPlacesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NearbyPlacesRepositoryImpl @Inject constructor(
    private val cache: NearbyPlacesCache
) : NearbyPlacesRepository {

    /** Mirror that answered last; tried first next time instead of always starting with the list head. */
    @Volatile
    private var preferredMirror: String? = null

    /**
     * One OSM tag test. [values] == null matches any value of [key].
     * Places without a name are dropped unless [allowUnnamed] is set (fuel stations, mosques, ATMs...
     * are still useful on the map even when nobody has named them).
     */
    private class TagRule(
        val key: String,
        val values: Set<String>? = null,
        val allowUnnamed: Boolean = false
    ) {
        fun matches(tags: JSONObject): Boolean {
            val v = tags.optString(key)
            return v.isNotEmpty() && (values == null || v.lowercase(Locale.US) in values)
        }
    }

    private class Classification(val category: NearbyCategory, val rule: TagRule?)

    companion object {
        private const val TAG = "NearbyPlacesRepo"

        /** Runs the hedged mirror requests independently of the caller (see fetchFromMirrors). */
        private val mirrorScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        private val USER_AGENT get() = com.autonoumus.ai.core.util.AppLinks.userAgent

        private const val MIN_RADIUS_METERS = 1000
        private const val MAX_RADIUS_METERS = 30000
        private const val MAX_RESULTS = 400
        /** How long a mirror may think before the next one is asked in parallel. */
        private const val HEDGE_DELAY_MS = 1500L
        private const val DUPLICATE_DISTANCE_METERS = 60f

        // Prioritized global Overpass mirrors (fastest first - measured 2026-09: overpass-api.de ~3 s,
        // mail.ru ~17 s, kumi often timing out; the winner of each request is preferred afterwards).
        private val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter"
        )
        /** The first N mirrors (different operators) are asked at once; the rest are hedges. */
        private const val PARALLEL_START = 2

        private const val NAMED = "[\"name\"]"

        /** Keys whose every value is worth listing in ALL mode (named features only). Other keys use curated values. */
        private val ANY_VALUE_KEYS_IN_ALL = setOf("shop", "historic")

        /** Keys used to describe a feature that matched no category. */
        private val DESCRIPTIVE_KEYS = listOf("amenity", "healthcare", "tourism", "shop", "leisure", "historic")

        /** Extra (named) values shown under "All Places" that have no category chip of their own. */
        private val ALL_EXTRA_VALUES = mapOf(
            "amenity" to setOf("post_office", "library", "townhall", "courthouse", "community_centre", "embassy", "veterinary", "childcare"),
            "healthcare" to setOf("doctor", "dentist", "clinic", "hospital", "laboratory", "physiotherapist", "optometrist")
        )

        /** Too many, too small: parking lots would crowd everything else out of "All Places". */
        private val EXCLUDED_FROM_ALL = setOf(NearbyCategory.PARKING)

        private fun rule(key: String, allowUnnamed: Boolean = false, vararg values: String) =
            TagRule(key, values.takeIf { it.isNotEmpty() }?.map { it.lowercase(Locale.US) }?.toSet(), allowUnnamed)

        /**
         * Single source of truth for both the Overpass query and the classification of results.
         * Order matters when classifying: the first category with a matching rule wins, so specific
         * categories (pharmacy, groceries, ...) must come before broad ones (shopping, hospitals).
         */
        private val CATEGORY_RULES: Map<NearbyCategory, List<TagRule>> = linkedMapOf(
            NearbyCategory.PHARMACY to listOf(
                rule("amenity", true, "pharmacy"),
                rule("healthcare", true, "pharmacy"),
                rule("shop", true, "chemist")
            ),
            NearbyCategory.HOSPITALS to listOf(
                rule("amenity", true, "hospital", "clinic", "doctors", "dentist"),
                rule("healthcare")
            ),
            NearbyCategory.FUEL to listOf(rule("amenity", true, "fuel")),
            NearbyCategory.EV_CHARGING to listOf(rule("amenity", true, "charging_station")),
            NearbyCategory.RESTAURANTS to listOf(
                rule("amenity", false, "restaurant", "fast_food", "food_court")
            ),
            NearbyCategory.CAFES to listOf(
                rule("amenity", false, "cafe"),
                rule("shop", false, "coffee", "bakery", "pastry")
            ),
            NearbyCategory.HOTELS to listOf(
                rule("tourism", false, "hotel", "motel", "guest_house", "hostel", "resort", "apartment")
            ),
            NearbyCategory.EDUCATION to listOf(
                rule("amenity", true, "college", "school", "university", "kindergarten")
            ),
            NearbyCategory.BANKS to listOf(rule("amenity", true, "bank", "atm")),
            NearbyCategory.GROCERY to listOf(
                rule("shop", false, "supermarket", "convenience", "greengrocer", "grocery", "general", "butcher", "deli", "seafood", "farm")
            ),
            NearbyCategory.CAR_SERVICES to listOf(
                rule("shop", false, "car_repair", "car", "car_parts", "tyres", "motorcycle"),
                rule("amenity", false, "car_wash", "car_rental", "car_repair")
            ),
            NearbyCategory.SHOPPING to listOf(
                rule("shop"),
                rule("amenity", false, "marketplace")
            ),
            NearbyCategory.FITNESS to listOf(
                rule("leisure", false, "fitness_centre", "sports_centre", "stadium", "swimming_pool", "pitch", "sports_hall")
            ),
            NearbyCategory.ENTERTAINMENT to listOf(
                rule("amenity", false, "cinema", "theatre", "nightclub", "arts_centre", "casino", "bar", "pub"),
                rule("leisure", false, "amusement_arcade", "bowling_alley", "water_park")
            ),
            NearbyCategory.PARKS to listOf(
                rule("leisure", false, "park", "garden", "playground", "nature_reserve", "dog_park")
            ),
            NearbyCategory.TOURISM to listOf(
                rule("tourism", false, "attraction", "museum", "viewpoint", "zoo", "theme_park", "gallery", "aquarium", "picnic_site"),
                rule("historic")
            ),
            NearbyCategory.MOSQUE to listOf(rule("amenity", true, "place_of_worship")),
            NearbyCategory.POLICE to listOf(rule("amenity", true, "police", "fire_station")),
            NearbyCategory.TRANSPORT to listOf(
                rule("amenity", false, "bus_station", "ferry_terminal", "taxi"),
                rule("railway", false, "station", "halt"),
                rule("public_transport", false, "station"),
                rule("aeroway", false, "aerodrome", "terminal")
            ),
            NearbyCategory.PARKING to listOf(rule("amenity", false, "parking"))
        )
    }

    override suspend fun getNearbyPlaces(
        latitude: Double,
        longitude: Double,
        category: NearbyCategory,
        radiusMeters: Int,
        searchQuery: String
    ): List<NearbyPlace> = withContext(Dispatchers.IO) {
        val searchRadius = radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
        val queryQL = buildOverpassQuery(latitude, longitude, category, searchRadius)

        val root = fetchFromMirrors(queryQL)
        // The query covers the circle's bounding box; drop the corners outside the radius.
        var places = parseOverpassResponse(root, latitude, longitude)
            .filter { it.distanceMeters <= searchRadius }
        // When Overpass hit the result limit the list is an arbitrary subset of the circle, so the
        // cache must not reuse it for a smaller radius (it would miss places that were cut off).
        val complete = (root.optJSONArray("elements")?.length() ?: 0) < MAX_RESULTS

        // A category query can also return neighbours (e.g. healthcare=pharmacy under Hospitals)
        if (category != NearbyCategory.ALL) {
            places = places.filter { it.categoryType == category }
        }

        // Cached before the keyword filter so the same entry serves any later keyword
        cache.put(latitude, longitude, category, searchRadius, complete, places)

        applyKeyword(places, searchQuery).sortedBy { it.distanceMeters }
    }

    override suspend fun getCachedNearbyPlaces(
        latitude: Double,
        longitude: Double,
        category: NearbyCategory,
        radiusMeters: Int,
        searchQuery: String
    ): CachedNearbyPlaces? {
        val searchRadius = radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
        val hit = cache.find(latitude, longitude, category, searchRadius) ?: return null
        return CachedNearbyPlaces(applyKeyword(hit.places, searchQuery), hit.ageMs)
    }

    private fun applyKeyword(places: List<NearbyPlace>, searchQuery: String): List<NearbyPlace> {
        if (searchQuery.isBlank()) return places
        val q = searchQuery.trim().lowercase(Locale.US)
        return places.filter { place ->
            place.name.lowercase(Locale.US).contains(q) ||
                    place.categoryName.lowercase(Locale.US).contains(q) ||
                    place.address.lowercase(Locale.US).contains(q)
        }
    }

    /**
     * Uses a global [bbox:...] around the circle instead of (around:...) on every clause. Measured
     * 2026-09 against overpass-api.de for "All Places" (2 km): bbox 3.0 s every time, around
     * 504 / 30 s timeouts - the per-clause distance filter is what overloads busy public mirrors.
     * Places in the box corners are removed afterwards by distance.
     */
    private fun buildOverpassQuery(
        lat: Double,
        lon: Double,
        category: NearbyCategory,
        radius: Int
    ): String {
        val dLat = radius / 111_320.0
        val dLon = radius / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        val clauses = linkedSetOf<String>()

        fun clause(key: String, values: Collection<String>?, named: Boolean) {
            val selector = if (values == null) "[\"$key\"]" else "[\"$key\"~\"^(${values.joinToString("|")})\$\"]"
            clauses += "nwr$selector${if (named) NAMED else ""};"
        }

        if (category == NearbyCategory.ALL) {
            // Value lists go through Overpass' tag index and stay fast on busy public mirrors;
            // "any amenity" would also drag in every bench and bin.
            val unnamed = linkedMapOf<String, MutableSet<String>>()
            val named = linkedMapOf<String, MutableSet<String>>()
            val anyValue = linkedSetOf<String>()

            for ((cat, rules) in CATEGORY_RULES) {
                if (cat in EXCLUDED_FROM_ALL) continue
                for (rule in rules) {
                    when {
                        rule.values == null -> if (rule.key in ANY_VALUE_KEYS_IN_ALL) anyValue += rule.key // others: see ALL_EXTRA_VALUES
                        rule.key in ANY_VALUE_KEYS_IN_ALL && !rule.allowUnnamed -> Unit // covered by the any-value scan
                        else -> (if (rule.allowUnnamed) unnamed else named)
                            .getOrPut(rule.key) { linkedSetOf() }.addAll(rule.values)
                    }
                }
            }
            ALL_EXTRA_VALUES.forEach { (key, values) -> named.getOrPut(key) { linkedSetOf() }.addAll(values) }

            // Named-only values that are also allowed unnamed don't need a second, stricter scan
            unnamed.forEach { (key, values) -> clause(key, values, named = false) }
            named.forEach { (key, values) ->
                val namedOnly = values - unnamed[key].orEmpty()
                if (namedOnly.isNotEmpty()) clause(key, namedOnly, named = true)
            }
            // shop=chemist etc. are already covered above; every other shop needs a name
            anyValue.forEach { key -> clause(key, null, named = true) }
        } else {
            CATEGORY_RULES[category].orEmpty().forEach { rule ->
                clause(rule.key, rule.values, named = !rule.allowUnnamed)
            }
        }

        return "[out:json][timeout:25][bbox:$bbox];(${clauses.joinToString("")});out center tags $MAX_RESULTS;"
    }

    /**
     * Hedged requests across the mirrors. Previously the mirrors were tried strictly one after
     * another, so one slow or overloaded server (common on the free public instances) made the
     * user wait up to its full 30s read timeout before the next mirror was even asked.
     *
     * Now the preferred mirror gets a [HEDGE_DELAY_MS] head start; if it hasn't answered by then
     * (or fails earlier) the next one is started in parallel, and so on. The first valid answer
     * wins and the others are aborted. Throws when no mirror produced a usable answer.
     */
    private suspend fun fetchFromMirrors(queryQL: String): JSONObject = coroutineScope {
        val mirrors = preferredMirror
            ?.let { preferred -> listOf(preferred) + (OVERPASS_ENDPOINTS - preferred) }
            ?: OVERPASS_ENDPOINTS
        val winner = CompletableDeferred<JSONObject>()
        val failures = AtomicInteger(0)
        val requests = mutableListOf<Job>()
        val connections = java.util.Collections.synchronizedList(mutableListOf<HttpURLConnection>())

        for (endpoint in mirrors) {
            val failed = CompletableDeferred<Unit>()
            // Not children of this scope: coroutineScope would otherwise wait for every losing mirror
            // to finish its blocking socket read (a slow 504 kept the answer hidden ~13 s after the
            // winner had already replied - measured on device). Losers are aborted below.
            requests += mirrorScope.launch {
                try {
                    val t0 = System.currentTimeMillis()
                    val root = queryMirror(endpoint, queryQL, connections)
                    Log.d(TAG, "Mirror $endpoint answered in ${System.currentTimeMillis() - t0} ms")
                    if (winner.complete(root)) preferredMirror = endpoint
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Mirror $endpoint failed: ${e.message}")
                    if (failures.incrementAndGet() == mirrors.size) {
                        winner.completeExceptionally(IOException("All Overpass mirrors failed", e))
                    }
                    failed.complete(Unit)
                }
            }
            if (requests.size < PARALLEL_START) continue
            // Head start for this mirror: move on early if it fails, stop if anyone has answered
            withTimeoutOrNull(HEDGE_DELAY_MS) {
                select<Unit> {
                    winner.onJoin {}
                    failed.onJoin {}
                }
            }
            if (winner.isCompleted) break
        }

        try {
            winner.await()
        } finally {
            requests.forEach { it.cancel() }
            // Blocking reads ignore coroutine cancellation; closing the socket aborts them.
            synchronized(connections) { connections.forEach { runCatching { it.disconnect() } } }
        }
    }

    /** One Overpass request. Returns the parsed root, or throws if the answer is unusable. */
    private suspend fun queryMirror(
        endpoint: String,
        queryQL: String,
        connections: MutableList<HttpURLConnection>
    ): JSONObject {
        val root = JSONObject(executePostRequest(endpoint, queryQL, connections))
        val elements = root.optJSONArray("elements")
            ?: throw IOException("Malformed response from $endpoint")
        // Overpass answers HTTP 200 with an empty list + remark when the server ran out of time
        if (elements.length() == 0 && root.optString("remark").contains("error", ignoreCase = true)) {
            throw IOException(root.optString("remark"))
        }
        return root
    }

    private suspend fun executePostRequest(
        urlString: String,
        postData: String,
        connections: MutableList<HttpURLConnection>
    ): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        connections += conn
        // Blocking socket reads ignore coroutine cancellation; disconnecting from the cancelling
        // thread aborts them so a losing mirror request doesn't keep running in the background.
        val abortOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) conn.disconnect()
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.doInput = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")

            val encoded = "data=" + URLEncoder.encode(postData, "UTF-8")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(encoded)
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    return reader.readText()
                }
            } else {
                throw IOException("HTTP Error: $responseCode")
            }
        } finally {
            abortOnCancel?.dispose()
            conn.disconnect()
        }
    }

    private fun parseOverpassResponse(
        root: JSONObject,
        userLat: Double,
        userLon: Double
    ): List<NearbyPlace> {
        val results = mutableListOf<NearbyPlace>()
        // Same-name places that are (nearly) at the same spot are one venue mapped twice
        // (e.g. a hospital as a node and as its building outline). Chains with several branches stay.
        val acceptedByName = mutableMapOf<String, MutableList<Pair<Double, Double>>>()

        try {
            val elements = root.optJSONArray("elements") ?: return emptyList()

            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                val tags = element.optJSONObject("tags") ?: continue

                val classification = classify(tags)

                // Name
                var name = tags.optString("name").trim()
                if (name.isBlank()) name = tags.optString("name:en").trim()
                if (name.isBlank()) name = tags.optString("brand").trim()
                if (name.isBlank()) name = tags.optString("operator").trim()
                val categoryTitle = describe(classification, tags)
                if (name.isBlank()) {
                    if (classification.rule?.allowUnnamed != true) continue
                    name = categoryTitle
                }

                // Coordinates (node lat/lon or way/relation center)
                var lat = element.optDouble("lat", Double.NaN)
                var lon = element.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) {
                    val center = element.optJSONObject("center")
                    if (center != null) {
                        lat = center.optDouble("lat", Double.NaN)
                        lon = center.optDouble("lon", Double.NaN)
                    }
                }
                if (lat.isNaN() || lon.isNaN()) continue

                val nameKey = name.lowercase(Locale.US)
                val sameName = acceptedByName.getOrPut(nameKey) { mutableListOf() }
                val tmp = FloatArray(1)
                val isDuplicate = sameName.any { (otherLat, otherLon) ->
                    Location.distanceBetween(lat, lon, otherLat, otherLon, tmp)
                    tmp[0] < DUPLICATE_DISTANCE_METERS
                }
                if (isDuplicate) continue
                sameName.add(lat to lon)

                // Distance
                val distanceResults = FloatArray(1)
                Location.distanceBetween(userLat, userLon, lat, lon, distanceResults)
                val distanceMeters = distanceResults[0].toDouble()

                // Address
                val street = tags.optString("addr:street").ifBlank { tags.optString("street") }
                val city = tags.optString("addr:city")
                    .ifBlank { tags.optString("addr:suburb") }
                    .ifBlank { tags.optString("city") }
                val houseNumber = tags.optString("addr:housenumber")
                val streetLine = listOf(houseNumber, street).filter { it.isNotBlank() }.joinToString(" ")
                val address = tags.optString("addr:full").ifBlank {
                    listOf(streetLine, city).filter { it.isNotBlank() }.joinToString(", ")
                }.ifBlank { "$categoryTitle near you" }

                // Metadata
                // Mappers use several keys for numbers; missing any of them hid real phone numbers
                val phone = firstNonBlank(
                    tags, "phone", "contact:phone", "contact:mobile", "mobile", "phone:mobile",
                    "contact:telephone", "telephone", "contact:whatsapp"
                )
                val website = firstNonBlank(tags, "website", "contact:website", "url")
                val openingHours = tags.optString("opening_hours").takeIf { it.isNotBlank() }

                val tagMap = mutableMapOf<String, String>()
                val keys = tags.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    tagMap[k] = tags.optString(k)
                }

                results.add(
                    NearbyPlace(
                        id = "${element.optString("type", "node")}_${element.optLong("id")}",
                        name = name,
                        categoryName = categoryTitle,
                        categoryType = classification.category,
                        latitude = lat,
                        longitude = lon,
                        distanceMeters = distanceMeters,
                        address = address,
                        street = street,
                        city = city,
                        phone = phone,
                        website = website,
                        openingHours = openingHours,
                        isOpen = OpeningHoursParser.isOpenNow(openingHours),
                        tags = tagMap
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Overpass response: ${e.message}", e)
        }
        return results
    }

    private fun firstNonBlank(tags: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> tags.optString(key).trim().takeIf { it.isNotEmpty() } }

    /** Tags decide the category; the first category (in [CATEGORY_RULES] order) with a matching rule wins. */
    private fun classify(tags: JSONObject): Classification {
        for ((category, rules) in CATEGORY_RULES) {
            val matched = rules.firstOrNull { it.matches(tags) }
            if (matched != null) return Classification(category, matched)
        }
        return Classification(NearbyCategory.ALL, null)
    }

    /** Human readable type of the place, e.g. "Mosque", "Fast Food", "Fuel Station". */
    private fun describe(classification: Classification, tags: JSONObject): String {
        val rule = classification.rule
        val value = rule?.let { tags.optString(it.key) }.orEmpty().lowercase(Locale.US)
        return when {
            classification.category == NearbyCategory.PHARMACY -> "Pharmacy"
            value == "fuel" -> "Fuel Station"
            value == "charging_station" -> "EV Charging"
            value == "place_of_worship" -> when (tags.optString("religion").lowercase(Locale.US)) {
                "muslim" -> "Mosque"
                "christian" -> "Church"
                "hindu" -> "Temple"
                "sikh" -> "Gurdwara"
                "buddhist" -> "Buddhist Temple"
                else -> "Place of Worship"
            }
            rule?.key == "historic" -> "Historic Site"
            rule?.key == "shop" && value == "yes" -> "Shop"
            value.isNotBlank() -> value.replace('_', ' ').capitalizeWords()
            else -> {
                // Unclassified feature: describe it by whichever generic tag it has
                DESCRIPTIVE_KEYS.firstNotNullOfOrNull { key ->
                    tags.optString(key).takeIf { it.isNotBlank() && it != "yes" }
                }?.replace('_', ' ')?.capitalizeWords() ?: "Point of Interest"
            }
        }
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }
}
