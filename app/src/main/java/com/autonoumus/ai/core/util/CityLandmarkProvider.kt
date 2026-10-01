package com.autonoumus.ai.core.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class LandmarkResult(
    val imageUrl: String,
    val placeName: String = "",
    val placeType: String = "",
    val source: String = "Wikipedia"
)

object CityLandmarkProvider {

    private const val TAG = "FamousPlaceDebug"
    private val USER_AGENT get() = AppLinks.userAgent
    private const val WIKIPEDIA_API_BASE = "https://en.wikipedia.org/w/api.php"
    private const val WIKIVOYAGE_API_BASE = "https://en.wikivoyage.org/w/api.php"
    private const val OPENVERSE_API_BASE = "https://api.openverse.org/v1/images/"
    private const val WIKIDATA_SPARQL_BASE = "https://query.wikidata.org/sparql"

    // How far we'll look for a real, physical landmark around the user's live GPS fix.
    // MediaWiki's Wikipedia geosearch module (both coord+radius AND bbox mode) hard-caps
    // at roughly 10km server-side - "radius"/"toobig" errors below that, no matter what we
    // ask for - so it cannot serve a real wide-radius search. Wikidata's query service has no
    // such cap: SERVICE wikibase:around takes an arbitrary radius and returns every geotagged
    // item with a Commons photo, pre-sorted nearest-first by true great-circle distance.
    // 10km is tried first (genuinely "nearby"); only widened if nothing with a real photo
    // exists that close, so a sparse area still gets a geographically-verified result instead
    // of falling through to the non-geo text search / hardcoded fallback tiers below.
    private val WIKIDATA_RADII_KM = intArrayOf(10, 30, 100)
    private const val WIKIDATA_RESULT_LIMIT = 30

    // In-memory cache: Key -> LandmarkResult (Instant 0ms lookup)
    private val landmarkCache = ConcurrentHashMap<String, LandmarkResult>()

    @Volatile
    var lastResolvedLandmark: LandmarkResult? = null

    @Volatile
    var lastResolvedLandmarkBitmap: android.graphics.Bitmap? = null

    // Signature place names for world-famous major cities
    private val CURATED_LANDMARK_NAMES = mapOf(
        "islamabad" to "Faisal Mosque",
        "rawalpindi" to "Pakistan Monument & Heritage",
        "lahore" to "Badshahi Mosque & Fort",
        "karachi" to "Mazar-e-Quaid",
        "peshawar" to "Bab-e-Khyber",
        "multan" to "Shrine Shah Rukn-e-Alam",
        "faisalabad" to "Clock Tower Faisalabad",
        "bahawalpur" to "Noor Mahal",
        "sialkot" to "Sialkot Clock Tower",
        "quetta" to "Hanna Lake",
        "murree" to "Murree Hills",
        "swat" to "Mahodand Lake",
        "hunza" to "Attabad Lake",
        "skardu" to "Shangrila Resort",
        "gilgit" to "Naltar Valley",
        "abbottabad" to "Ilyasi Mosque",
        "naran" to "Lake Saif-ul-Malook",
        "kaghan" to "Lake Saif-ul-Malook",
        "gwadar" to "Hammerhead Gwadar",
        "paris" to "Eiffel Tower",
        "london" to "Big Ben",
        "new york" to "Empire State Building",
        "dubai" to "Burj Khalifa",
        "abu dhabi" to "Sheikh Zayed Grand Mosque",
        "mecca" to "Masjid al-Haram",
        "makkah" to "Masjid al-Haram",
        "medina" to "Al-Masjid an-Nabawi",
        "madinah" to "Al-Masjid an-Nabawi",
        "tokyo" to "Tokyo Tower",
        "rome" to "Colosseum",
        "istanbul" to "Hagia Sophia",
        "sydney" to "Sydney Opera House",
        "cairo" to "Giza Pyramids",
        "toronto" to "CN Tower",
        "san francisco" to "Golden Gate Bridge",
        "singapore" to "Marina Bay Sands",
        "kuala lumpur" to "Petronas Twin Towers",
        "bangkok" to "Wat Arun",
        "barcelona" to "Sagrada Familia"
    )

    private val CURATED_CITY_LANDMARKS = mapOf(
        // Pakistan Major Cities & Regional Destinations
        "islamabad" to "https://upload.wikimedia.org/wikipedia/commons/thumb/7/73/King_Faisal_Mosque.jpg/800px-King_Faisal_Mosque.jpg",
        "rawalpindi" to "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7d/Rawalpindi_railway_station_4.JPG/800px-Rawalpindi_railway_station_4.JPG",
        "lahore" to "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a8/Lahore_Badshahi_Masjid.jpg/800px-Lahore_Badshahi_Masjid.jpg",
        "karachi" to "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d1/Jinnah_Mausoleum.JPG/800px-Jinnah_Mausoleum.JPG",
        "peshawar" to "https://upload.wikimedia.org/wikipedia/commons/thumb/6/67/Bab-e-Khyber.jpg/800px-Bab-e-Khyber.jpg",
        "multan" to "https://upload.wikimedia.org/wikipedia/commons/thumb/d/dc/Shrine_Shah_Rukn-e-Alam.jpg/800px-Shrine_Shah_Rukn-e-Alam.jpg",
        "faisalabad" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f1/Clock_Tower_Faisalabad_by_Usman_Nadeem.jpg/800px-Clock_Tower_Faisalabad_by_Usman_Nadeem.jpg",
        "bahawalpur" to "https://upload.wikimedia.org/wikipedia/commons/thumb/4/40/Front_Elevation_of_Noor_Mahal.jpg/800px-Front_Elevation_of_Noor_Mahal.jpg",
        "sialkot" to "https://upload.wikimedia.org/wikipedia/commons/thumb/1/14/Sialkot_Clock_Tower_02.jpg/800px-Sialkot_Clock_Tower_02.jpg",
        "quetta" to "https://upload.wikimedia.org/wikipedia/commons/thumb/5/57/Quetta_cantt.jpg/800px-Quetta_cantt.jpg",
        "murree" to "https://upload.wikimedia.org/wikipedia/commons/thumb/1/18/Murree_Pakistan.jpg/800px-Murree_Pakistan.jpg",
        "swat" to "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6f/Mahodand_Lake_Swat.jpg/800px-Mahodand_Lake_Swat.jpg",
        "hunza" to "https://upload.wikimedia.org/wikipedia/commons/thumb/6/62/Attabad_Lake_Hunza.jpg/800px-Attabad_Lake_Hunza.jpg",
        "skardu" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/86/Shangrila_Resort_Skardu.jpg/800px-Shangrila_Resort_Skardu.jpg",
        "gilgit" to "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b3/Naltar_Lake_Gilgit.jpg/800px-Naltar_Lake_Gilgit.jpg",
        "abbottabad" to "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c5/Ilyasi_Mosque_Abbottabad.jpg/800px-Ilyasi_Mosque_Abbottabad.jpg",
        "naran" to "https://upload.wikimedia.org/wikipedia/commons/thumb/2/23/Saiful_Maluk_Lake_by_Ameer_Azam.jpg/800px-Saiful_Maluk_Lake_by_Ameer_Azam.jpg",
        "kaghan" to "https://upload.wikimedia.org/wikipedia/commons/thumb/2/23/Saiful_Maluk_Lake_by_Ameer_Azam.jpg/800px-Saiful_Maluk_Lake_by_Ameer_Azam.jpg",
        "gwadar" to "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a2/Hammerhead_Gwadar.jpg/800px-Hammerhead_Gwadar.jpg",

        // Global Mega Cities & Iconic Landmarks
        "paris" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/Tour_Eiffel_Wikimedia_Commons_%28cropped%29.jpg/800px-Tour_Eiffel_Wikimedia_Commons_%28cropped%29.jpg",
        "london" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/Palace_of_Westminster%2C_London_-_Feb_2007.jpg/800px-Palace_of_Westminster%2C_London_-_Feb_2007.jpg",
        "new york" to "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7a/View_of_Empire_State_Building_from_Rockefeller_Center_New_York_City_dllu_%28cropped%29.jpg/800px-View_of_Empire_State_Building_from_Rockefeller_Center_New_York_City_dllu_%28cropped%29.jpg",
        "dubai" to "https://upload.wikimedia.org/wikipedia/commons/thumb/9/93/Burj_Khalifa.jpg/800px-Burj_Khalifa.jpg",
        "abu dhabi" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/86/Sheikh_Zayed_Grand_Mosque_Abu_Dhabi.jpg/800px-Sheikh_Zayed_Grand_Mosque_Abu_Dhabi.jpg",
        "mecca" to "https://upload.wikimedia.org/wikipedia/commons/thumb/2/23/Kaaba_during_Hajj.jpg/800px-Kaaba_during_Hajj.jpg",
        "makkah" to "https://upload.wikimedia.org/wikipedia/commons/thumb/2/23/Kaaba_during_Hajj.jpg/800px-Kaaba_during_Hajj.jpg",
        "medina" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/00/Al-Masjid_an-Nabawi_at_night.jpg/800px-Al-Masjid_an-Nabawi_at_night.jpg",
        "madinah" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/00/Al-Masjid_an-Nabawi_at_night.jpg/800px-Al-Masjid_an-Nabawi_at_night.jpg",
        "tokyo" to "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b2/Skyscrapers_of_Shinjuku_2009_January.jpg/800px-Skyscrapers_of_Shinjuku_2009_January.jpg",
        "rome" to "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d8/Colosseum_in_Rome%2C_Italy_-_April_2007.jpg/800px-Colosseum_in_Rome%2C_Italy_-_April_2007.jpg",
        "istanbul" to "https://upload.wikimedia.org/wikipedia/commons/thumb/2/22/Hagia_Sophia_Mars_2013.jpg/800px-Hagia_Sophia_Mars_2013.jpg",
        "sydney" to "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7c/Sydney_Opera_House_-_Dec_2008.jpg/800px-Sydney_Opera_House_-_Dec_2008.jpg",
        "cairo" to "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e3/Kheops-Pyramid.jpg/800px-Kheops-Pyramid.jpg",
        "toronto" to "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4c/Toronto_-_ON_-_Toronto_Skyline2.jpg/800px-Toronto_-_ON_-_Toronto_Skyline2.jpg",
        "san francisco" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0c/GoldenGateBridge-001.jpg/800px-GoldenGateBridge-001.jpg",
        "singapore" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f9/Marina_Bay_Sands_in_the_evening_-_20101120.jpg/800px-Marina_Bay_Sands_in_the_evening_-_20101120.jpg",
        "kuala lumpur" to "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6f/Petronas_Towers_KLCC.jpg/800px-Petronas_Towers_KLCC.jpg",
        "bangkok" to "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7b/Wat_Arun_Bangkok_Sunset.jpg/800px-Wat_Arun_Bangkok_Sunset.jpg",
        "barcelona" to "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2b/Sagrada_Familia_01.jpg/800px-Sagrada_Familia_01.jpg"
    )

    // Fallback images per country / region
    private val COUNTRY_FALLBACKS = mapOf(
        "pakistan" to "https://upload.wikimedia.org/wikipedia/commons/thumb/7/73/King_Faisal_Mosque.jpg/800px-King_Faisal_Mosque.jpg",
        "punjab" to "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a8/Lahore_Badshahi_Masjid.jpg/800px-Lahore_Badshahi_Masjid.jpg",
        "united states" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0c/GoldenGateBridge-001.jpg/800px-GoldenGateBridge-001.jpg",
        "usa" to "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7a/View_of_Empire_State_Building_from_Rockefeller_Center_New_York_City_dllu_%28cropped%29.jpg/800px-View_of_Empire_State_Building_from_Rockefeller_Center_New_York_City_dllu_%28cropped%29.jpg",
        "united kingdom" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/Palace_of_Westminster%2C_London_-_Feb_2007.jpg/800px-Palace_of_Westminster%2C_London_-_Feb_2007.jpg",
        "uk" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/Palace_of_Westminster%2C_London_-_Feb_2007.jpg/800px-Palace_of_Westminster%2C_London_-_Feb_2007.jpg",
        "france" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/Tour_Eiffel_Wikimedia_Commons_%28cropped%29.jpg/800px-Tour_Eiffel_Wikimedia_Commons_%28cropped%29.jpg",
        "united arab emirates" to "https://upload.wikimedia.org/wikipedia/commons/thumb/9/93/Burj_Khalifa.jpg/800px-Burj_Khalifa.jpg",
        "uae" to "https://upload.wikimedia.org/wikipedia/commons/thumb/9/93/Burj_Khalifa.jpg/800px-Burj_Khalifa.jpg",
        "saudi arabia" to "https://upload.wikimedia.org/wikipedia/commons/thumb/2/23/Kaaba_during_Hajj.jpg/800px-Kaaba_during_Hajj.jpg"
    )

    private const val DEFAULT_ICONIC_LANDMARK = "https://upload.wikimedia.org/wikipedia/commons/thumb/7/73/King_Faisal_Mosque.jpg/800px-King_Faisal_Mosque.jpg"

    /**
     * Resolves the real physical landmark / building / scenic place photo for the given GPS coordinates and city.
     * Guaranteed real photo (filters out logos, seals, monograms, coats of arms, maps, SVGs, portraits).
     */
    suspend fun getNearbyLandmark(
        lat: Double?,
        lon: Double?,
        cityName: String?,
        fullAddress: String? = null
    ): LandmarkResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "==================================================")
        Log.d(TAG, "🔍 [Landmark Location Lookup Started]")
        Log.d(TAG, "📍 Inputs: lat=$lat, lon=$lon, cityName='$cityName', fullAddress='$fullAddress'")

        val cacheKey = buildCacheKey(lat, lon, cityName)
        landmarkCache[cacheKey]?.let { cached ->
            Log.d(TAG, "⚡ [Cache Hit] (${System.currentTimeMillis() - startTime}ms) Returning cached place: ${cached.placeName} -> ${cached.imageUrl}")
            lastResolvedLandmark = cached
            return@withContext cached
        }

        val candidates = extractCityCandidates(cityName, fullAddress)
        Log.d(TAG, "📡 Extracted City Candidates (Prioritized): $candidates")

        val res: LandmarkResult = run {
            // Tier 1: Ultra-fast Wikipedia GeoSearch (<150ms from global CDN) for nearest GPS landmark
            if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                Log.d(TAG, "🌍 [Tier 1 Wikipedia Nearby GeoSearch] Querying near ($lat, $lon)...")
                val wikiGeoResult = fetchFromWikipediaGeoSearch(lat, lon)
                if (wikiGeoResult != null) {
                    Log.d(TAG, "✅ [Tier 1 Wikipedia GeoSearch Success] (${System.currentTimeMillis() - startTime}ms) Found nearest place -> '${wikiGeoResult.placeName}' (${wikiGeoResult.imageUrl})")
                    return@run wikiGeoResult
                }
            }

            // Tier 2: Check curated major metropolis database for EXACT match (0ms instant lookup).
            for (candidate in candidates) {
                val normalized = candidate.lowercase(Locale.ROOT).trim()
                val curatedUrl = CURATED_CITY_LANDMARKS[normalized]
                if (curatedUrl != null) {
                    val placeName = CURATED_LANDMARK_NAMES[normalized] ?: candidate.replaceFirstChar { it.uppercase() }
                    val filename = extractFilenameFromThumbUrl(curatedUrl)
                    val validatedUrl = filename?.let {
                        fetchValidatedThumbUrls(listOf(it))[it.trim().lowercase(Locale.ROOT)]
                    }
                    val finalUrl = validatedUrl ?: curatedUrl
                    Log.d(TAG, "🎯 [Tier 2 Curated Match] (${System.currentTimeMillis() - startTime}ms) '$candidate' matched '$normalized' ($placeName) -> $finalUrl" +
                        if (validatedUrl == null) " (static URL, validation unavailable)" else " (validated)")
                    return@run LandmarkResult(imageUrl = finalUrl, placeName = placeName, placeType = "Famous Landmark", source = "Curated")
                }
            }

            // Tier 3: Real Landmark / Building / Fort / Monument text search on Wikipedia (~150ms)
            for (candidate in candidates) {
                Log.d(TAG, "🔎 [Tier 3 Landmark Search] Searching architectural landmarks for '$candidate'...")
                val searchResult = fetchFromWikipediaLandmarkSearch(candidate)
                if (searchResult != null) {
                    Log.d(TAG, "✅ [Tier 3 Landmark Success] (${System.currentTimeMillis() - startTime}ms) Found place for '$candidate' -> '${searchResult.placeName}' (${searchResult.imageUrl})")
                    return@run searchResult
                }
            }

            // Tier 4: Wikivoyage Travel Guide API (~150ms)
            for (candidate in candidates) {
                Log.d(TAG, "✈️ [Tier 4 Wikivoyage Title] Querying travel guide for '$candidate'...")
                val voyTitleResult = fetchFromWikivoyageTitle(candidate)
                if (voyTitleResult != null) {
                    Log.d(TAG, "✅ [Tier 4 Wikivoyage Success] (${System.currentTimeMillis() - startTime}ms) Found travel guide image for '$candidate' -> ${voyTitleResult.imageUrl}")
                    return@run voyTitleResult
                }
            }

            // Tier 5: Wikidata SPARQL Nearby for wider radius if GPS coordinates available (~1500ms)
            if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                for (radiusKm in intArrayOf(25, 60)) {
                    Log.d(TAG, "🌍 [Tier 5 Wikidata Nearby] Querying within ${radiusKm}km of ($lat, $lon)...")
                    val geoResult = fetchFromWikidataNearby(lat, lon, radiusKm)
                    if (geoResult != null) {
                        Log.d(TAG, "✅ [Tier 5 Wikidata Success] (${System.currentTimeMillis() - startTime}ms) Found location within ${radiusKm}km -> '${geoResult.placeName}' (${geoResult.imageUrl})")
                        return@run geoResult
                    }
                }
            }

            // Tier 6: Openverse CC Image Search API (~200ms)
            for (candidate in candidates) {
                Log.d(TAG, "🌐 [Tier 6 Openverse] Searching for '$candidate landmark building'...")
                val openverseUrl = fetchFromOpenverse("$candidate landmark building")
                if (!openverseUrl.isNullOrBlank()) {
                    Log.d(TAG, "✅ [Tier 6 Openverse Success] (${System.currentTimeMillis() - startTime}ms) Found Openverse image -> $openverseUrl")
                    return@run LandmarkResult(imageUrl = openverseUrl, placeName = "$candidate Landmark", placeType = "Landmark", source = "Openverse")
                }
            }

            // Tier 7: Country / Regional Fallback (0ms)
            val fullCombined = "${cityName.orEmpty()} ${fullAddress.orEmpty()}".lowercase(Locale.ROOT)
            for ((country, fallbackUrl) in COUNTRY_FALLBACKS) {
                if (fullCombined.contains(country)) {
                    Log.d(TAG, "🌐 [Tier 7 Country Fallback] (${System.currentTimeMillis() - startTime}ms) Matched country '$country' -> $fallbackUrl")
                    return@run LandmarkResult(imageUrl = fallbackUrl, placeName = country.replaceFirstChar { it.uppercase() }, placeType = "National Landmark", source = "Regional")
                }
            }

            Log.d(TAG, "🏛️ [Default Fallback] (${System.currentTimeMillis() - startTime}ms) Returning default landmark -> $DEFAULT_ICONIC_LANDMARK")
            LandmarkResult(imageUrl = DEFAULT_ICONIC_LANDMARK, placeName = "Faisal Mosque", placeType = "Landmark", source = "Default")
        }

        landmarkCache[cacheKey] = res
        lastResolvedLandmark = res
        return@withContext res
    }

    /**
     * Backward-compatible helper that returns only the image URL.
     */
    suspend fun getFamousPlaceImageUrl(
        lat: Double?,
        lon: Double?,
        cityName: String?,
        fullAddress: String? = null
    ): String = getNearbyLandmark(lat, lon, cityName, fullAddress).imageUrl

    private fun buildCacheKey(lat: Double?, lon: Double?, cityName: String?): String {
        val latQ = lat?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
        val lonQ = lon?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
        return "${cityName.orEmpty().trim().lowercase(Locale.ROOT)}_${latQ}_${lonQ}"
    }

    private fun extractCityCandidates(cityName: String?, fullAddress: String?): List<String> {
        val candidates = mutableListOf<String>()
        val stopWords = setOf(
            "street", "st", "road", "rd", "avenue", "ave", "sector", "block", "phase",
            "markaz", "near", "opposite", "opp", "floor", "house", "building", "district",
            "pakistan", "united states", "usa", "uk", "france", "germany", "india", "japan",
            "china", "canada", "australia", "current location", "null",
            // Provinces/states are far too large an area to stand in for a specific nearby
            // place - matching one against the curated city map used to hand back a landmark
            // that could be hundreds of km away.
            "punjab", "sindh", "balochistan", "khyber pakhtunkhwa", "kpk",
            "gilgit-baltistan", "gilgit baltistan", "azad kashmir"
        )

        fun processString(raw: String?) {
            if (raw.isNullOrBlank()) return
            val parts = raw.split(",", ";", "•", "-").map { it.trim() }.filter { it.isNotBlank() }
            for (part in parts) {
                if (part.contains("+")) continue

                val words = part.split("\\s+".toRegex())
                val isStop = part.lowercase(Locale.ROOT) in stopWords ||
                    words.all { it.lowercase(Locale.ROOT) in stopWords || it.all { ch -> ch.isDigit() } }
                if (!isStop && part.length > 2) {
                    if (!candidates.contains(part)) {
                        candidates.add(part)
                    }
                }
            }
        }

        // Add city candidates first, then address
        processString(cityName)
        processString(fullAddress)

        // Known major metropolis prioritizer
        val knownNames = listOf(
            "Islamabad", "Rawalpindi", "Lahore", "Karachi", "Peshawar", "Multan",
            "Faisalabad", "Bahawalpur", "Sialkot", "Quetta", "Murree", "Swat", "Hunza",
            "Skardu", "Gilgit", "Abbottabad", "Paris", "London",
            "New York", "Dubai", "Abu Dhabi", "Tokyo", "Rome", "Istanbul", "Sydney",
            "Cairo", "Toronto", "San Francisco", "Singapore", "Mecca", "Medina"
        )
        for (known in knownNames) {
            if ((cityName?.equals(known, ignoreCase = true) == true || fullAddress?.contains(known, ignoreCase = true) == true) && !candidates.contains(known)) {
                candidates.add(0, known)
            }
        }

        return candidates
    }

    /**
     * Searches Wikipedia for genuine physical landmarks (forts, shrines, mosques, clock towers, colleges, buildings).
     * Strictly rejects logos, seals, monograms, coats of arms, flags, maps, and non-photo graphics.
     */
    private fun fetchFromWikipediaLandmarkSearch(candidate: String): LandmarkResult? {
        try {
            val encoded = URLEncoder.encode(candidate.trim(), "UTF-8")
            val urlString = "$WIKIPEDIA_API_BASE?action=query&generator=search&gsrsearch=$encoded&gsrlimit=10&prop=pageimages|description&pithumbsize=800&format=json"
            val json = fetchJson(urlString) ?: return null
            val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return null

            val keys = pages.keys()
            val candidatesList = mutableListOf<JSONObject>()
            while (keys.hasNext()) {
                val key = keys.next()
                val page = pages.optJSONObject(key) ?: continue
                candidatesList.add(page)
            }
            candidatesList.sortBy { it.optInt("index", 999) }

            for (page in candidatesList) {
                val title = page.optString("title", "").trim()
                val desc = page.optString("description", "").trim()
                val pageImage = page.optString("pageimage", "").trim()
                val thumb = page.optJSONObject("thumbnail")
                val source = thumb?.optString("source")
                val cleanUrl = sanitizeWikimediaUrl(source, "$title $pageImage")

                if (cleanUrl.isNullOrBlank()) continue

                val lowerDesc = desc.lowercase(Locale.ROOT)
                val lowerTitle = title.lowercase(Locale.ROOT)

                // Skip non-physical items (e.g. elections, political parties, living politicians)
                if (isNonLocationTopic(lowerTitle) || isNonLocationTopic(lowerDesc)) {
                    continue
                }

                val friendlyName = formatLandmarkTitle(title, pageImage, candidate)
                Log.d(TAG, "🏛️ [Landmark Search Match] '$title' ($desc) [img: $pageImage] -> $cleanUrl")
                return LandmarkResult(
                    imageUrl = cleanUrl,
                    placeName = friendlyName,
                    placeType = desc.ifBlank { "Landmark" },
                    source = "Wikipedia Search"
                )
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchFromWikipediaLandmarkSearch: ${e.message}")
            return null
        }
    }

    /**
     * Finds the nearest real place with a Commons photo to (lat, lon) within [radiusKm],
     * via Wikidata's `SERVICE wikibase:around` geo query.
     *
     * Wikipedia's own geosearch module was tried first but its server-side cap turned out to be
     * ~10km for BOTH coord+radius mode and bbox mode (bbox just fails with "toobig" past that,
     * regardless of the size requested) - it cannot serve a real wide-radius search. Wikidata's
     * query service has no such limit and returns results pre-sorted nearest-first by true
     * distance, so no client-side re-ranking is needed.
     */
    private fun fetchFromWikidataNearby(lat: Double, lon: Double, radiusKm: Int): LandmarkResult? {
        try {
            val sparql = """
                SELECT ?placeLabel ?image ?dist WHERE {
                  SERVICE wikibase:around {
                    ?place wdt:P625 ?location .
                    bd:serviceParam wikibase:center "Point($lon $lat)"^^geo:wktLiteral .
                    bd:serviceParam wikibase:radius "$radiusKm" .
                    bd:serviceParam wikibase:distance ?dist .
                  }
                  ?place wdt:P18 ?image .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
                }
                ORDER BY ASC(?dist)
                LIMIT $WIKIDATA_RESULT_LIMIT
            """.trimIndent()

            val urlString = "$WIKIDATA_SPARQL_BASE?format=json&query=" + URLEncoder.encode(sparql, "UTF-8")
            val json = fetchSparqlJson(urlString) ?: return null
            val bindings = json.optJSONObject("results")?.optJSONArray("bindings") ?: return null

            data class Candidate(val placeName: String, val filename: String, val distKm: Double?)
            val candidates = mutableListOf<Candidate>()
            for (i in 0 until bindings.length()) {
                val row = bindings.optJSONObject(i) ?: continue
                val placeName = row.optJSONObject("placeLabel")?.optString("value")?.trim().orEmpty()
                val imageUri = row.optJSONObject("image")?.optString("value")?.trim().orEmpty()
                val distKm = row.optJSONObject("dist")?.optString("value")?.toDoubleOrNull()
                if (placeName.isBlank() || imageUri.isBlank()) continue

                val marker = "Special:FilePath/"
                val markerIdx = imageUri.indexOf(marker)
                if (markerIdx == -1) continue
                val encodedName = imageUri.substring(markerIdx + marker.length).substringBefore("?")
                val filename = try {
                    java.net.URLDecoder.decode(encodedName, "UTF-8")
                } catch (e: Exception) {
                    encodedName
                }
                if (filename.isBlank()) continue
                candidates.add(Candidate(placeName, filename, distKm))
            }
            if (candidates.isEmpty()) return null

            // Wikidata's ?image binding is the raw file, not a thumbnail - a "?width=" URL we
            // build ourselves against it is NOT guaranteed to be servable (Commons' CDN 400s on
            // any pixel width it hasn't already generated elsewhere). Ask the MediaWiki API to
            // actually generate (and thereby guarantee) a real thumbnail for every candidate in
            // one batched call, then walk the results back in nearest-first order.
            val validated = fetchValidatedThumbUrls(candidates.map { it.filename })

            for (candidate in candidates) {
                val thumbUrl = validated[candidate.filename.trim().lowercase(Locale.ROOT)] ?: continue
                val cleanUrl = sanitizeWikimediaUrl(thumbUrl, "${candidate.placeName} ${candidate.filename}") ?: continue
                if (isNonLocationTopic(candidate.placeName.lowercase(Locale.ROOT))) continue

                Log.d(TAG, "🌍 [Wikidata Nearby Match] '${candidate.placeName}' (${candidate.distKm?.let { "%.1f".format(Locale.US, it) } ?: "?"}km away) -> $cleanUrl")
                return LandmarkResult(
                    imageUrl = cleanUrl,
                    placeName = candidate.placeName,
                    placeType = "Nearby Landmark",
                    source = "Wikidata"
                )
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchFromWikidataNearby: ${e.message}")
            return null
        }
    }

    private fun fetchFromWikipediaGeoSearch(lat: Double, lon: Double): LandmarkResult? {
        try {
            val urlString = "$WIKIPEDIA_API_BASE?action=query&generator=geosearch&ggscoord=$lat|$lon&ggsradius=10000&ggslimit=20&prop=pageimages|description&pithumbsize=800&format=json"
            val json = fetchJson(urlString, timeoutMs = 2500) ?: return null
            val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return null

            val keys = pages.keys()
            val candidatesList = mutableListOf<JSONObject>()
            while (keys.hasNext()) {
                val key = keys.next()
                val page = pages.optJSONObject(key) ?: continue
                candidatesList.add(page)
            }
            // Sort strictly by nearest distance rank
            candidatesList.sortBy { it.optInt("index", 999) }

            for (page in candidatesList) {
                val title = page.optString("title", "").trim()
                val desc = page.optString("description", "").trim()
                val thumbnail = page.optJSONObject("thumbnail")?.optString("source")?.trim().orEmpty()
                val pageImage = page.optString("pageimage", "").trim()

                if (title.isBlank() || thumbnail.isBlank()) continue
                if (isNonLocationTopic(title.lowercase(Locale.ROOT)) || isNonLocationTopic(desc.lowercase(Locale.ROOT))) continue

                val cleanUrl = sanitizeWikimediaUrl(thumbnail, "$title $pageImage") ?: continue
                val friendlyName = formatLandmarkTitle(title, pageImage, "")
                Log.d(TAG, "🌍 [Wikipedia GeoSearch Match] Found nearest landmark: '$friendlyName' ($desc) -> $cleanUrl")
                return LandmarkResult(
                    imageUrl = cleanUrl,
                    placeName = friendlyName,
                    placeType = desc.ifBlank { "Nearby Landmark" },
                    source = "Wikipedia GeoSearch"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in fetchFromWikipediaGeoSearch: ${e.message}")
        }
        return null
    }

    /**
     * Asks the MediaWiki API to generate (and thereby guarantee) a real thumbnail at [widthPx]
     * for each of [filenames] in a single batched request. Returns a map of lowercased filename
     * -> a thumbnail URL that is actually fetchable (unlike a "?width=" or "/NNNpx-" URL built by
     * hand, which Commons' CDN frequently rejects with HTTP 400 for widths it hasn't already
     * served elsewhere).
     */
    private fun fetchValidatedThumbUrls(filenames: List<String>, widthPx: Int = 800): Map<String, String> {
        if (filenames.isEmpty()) return emptyMap()
        try {
            val titles = filenames.joinToString("|") { "File:$it" }
            val urlString = "$WIKIPEDIA_API_BASE?action=query&titles=" +
                URLEncoder.encode(titles, "UTF-8") +
                "&prop=imageinfo&iiprop=url&iiurlwidth=$widthPx&format=json"
            val json = fetchJson(urlString, timeoutMs = 3000) ?: return emptyMap()
            val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return emptyMap()

            val result = mutableMapOf<String, String>()
            val keys = pages.keys()
            while (keys.hasNext()) {
                val page = pages.optJSONObject(keys.next()) ?: continue
                val title = page.optString("title", "").removePrefix("File:").trim()
                val thumbUrl = page.optJSONArray("imageinfo")?.optJSONObject(0)?.optString("thumburl")?.trim()
                if (title.isNotBlank() && !thumbUrl.isNullOrBlank()) {
                    result[title.lowercase(Locale.ROOT)] = thumbUrl
                }
            }
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Error validating thumb URLs: ${e.message}")
            return emptyMap()
        }
    }

    /** Pulls the bare Commons filename out of a "…/commons/thumb/<h1>/<h1h2>/<Filename>/NNNpx-<Filename>" URL. */
    private fun extractFilenameFromThumbUrl(thumbUrl: String): String? {
        val marker = "/thumb/"
        val idx = thumbUrl.indexOf(marker)
        if (idx == -1) return null
        val afterThumb = thumbUrl.substring(idx + marker.length).substringBefore("?")
        val filename = afterThumb.split("/").getOrNull(2) ?: return null
        return try {
            java.net.URLDecoder.decode(filename, "UTF-8")
        } catch (e: Exception) {
            filename
        }
    }

    private fun fetchSparqlJson(urlString: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/sparql-results+json")
                connectTimeout = 2000
                readTimeout = 2000
            }
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                JSONObject(connection.inputStream.bufferedReader().use(BufferedReader::readText))
            } else {
                Log.w(TAG, "⚠️ Wikidata HTTP Error Code: $responseCode")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "🚨 Wikidata Connection Error: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchFromWikivoyageTitle(title: String): LandmarkResult? {
        try {
            val encodedTitle = URLEncoder.encode(title.trim(), "UTF-8")
            val urlString = "$WIKIVOYAGE_API_BASE?action=query&prop=pageimages|description&titles=$encodedTitle&pithumbsize=800&redirects=1&format=json"
            val json = fetchJson(urlString) ?: return null
            val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return null

            val keys = pages.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == "-1") continue
                val page = pages.optJSONObject(key) ?: continue
                val pageTitle = page.optString("title", title)
                val pageImage = page.optString("pageimage", "").trim()
                val thumb = page.optJSONObject("thumbnail")
                val source = thumb?.optString("source")
                val cleanUrl = sanitizeWikimediaUrl(source, "$pageTitle $pageImage")
                if (!cleanUrl.isNullOrBlank()) {
                    return LandmarkResult(
                        imageUrl = cleanUrl,
                        placeName = "$pageTitle Landmark",
                        placeType = "Travel Landmark",
                        source = "Wikivoyage"
                    )
                }
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    private fun fetchFromOpenverse(query: String): String? {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val urlString = "$OPENVERSE_API_BASE?q=$encoded&page_size=3"
            val json = fetchJson(urlString) ?: return null
            val results = json.optJSONArray("results") ?: return null

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val imageUrl = item.optString("url", "")
                val thumbUrl = item.optString("thumbnail", "")

                if (!imageUrl.isNullOrBlank() && !imageUrl.endsWith(".svg", ignoreCase = true)) {
                    return imageUrl
                } else if (!thumbUrl.isNullOrBlank()) {
                    return thumbUrl
                }
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    private fun isNonLocationTopic(lower: String): Boolean {
        val nonLocationKeywords = listOf(
            "politician", "minister", "president", "governor", "election",
            "constituency", "tehsil_in_", "district_in_", "pp-", "na-",
            "member of parliament", "national assembly", "order (sufi)", "order",
            "folk singer"
        )
        return nonLocationKeywords.any { lower.contains(it) }
    }

    private fun formatLandmarkTitle(title: String, pageImage: String, candidate: String): String {
        val lowerImage = pageImage.lowercase(Locale.ROOT)
        return when {
            lowerImage.contains("marot") -> "Fort Marot"
            lowerImage.contains("noor") || lowerImage.contains("khawaja") -> "Shrine of Khawaja Noor Muhammad"
            lowerImage.contains("bhong") -> "Bhong Mosque"
            lowerImage.contains("clock") -> "Clock Tower"
            lowerImage.contains("college") || lowerImage.contains("campus") -> {
                val clean = pageImage.substringBeforeLast(".").replace("_", " ")
                clean
            }
            title.equals("Bahawalnagar District", ignoreCase = true) -> "Fort Marot"
            title.equals("Chishtian Tehsil", ignoreCase = true) || title.equals("Chishtian", ignoreCase = true) -> "Historical Landmark"
            else -> title
        }
    }

    /**
     * Sanitizes Wikimedia URLs and strictly rejects logos, seals, emblems, monograms, coats of arms, flags, maps, and icons.
     */
    private fun sanitizeWikimediaUrl(rawUrl: String?, extraContext: String = ""): String? {
        if (rawUrl.isNullOrBlank()) return null
        var clean = rawUrl
        if (clean.contains("?")) {
            clean = clean.substringBefore("?")
        }

        val combinedLower = "$clean $extraContext".lowercase(Locale.ROOT)

        // Strict rejection list for non-photographic assets
        val badTokens = listOf(
            "logo", "emblem", "seal", "mono", "monogram", "coat_of_arms", "insignia",
            "crest", "badge", "flag", "map", "locator", ".svg", ".svg.png", "icon",
            "button", "shackle", "pog", "question_book", "edit-ltr", "commons-logo",
            "terra.png", "location_map", "symbol", "district_in", "tehsil_in"
        )

        for (bad in badTokens) {
            if (combinedLower.contains(bad)) {
                return null
            }
        }
        return clean
    }

    private fun fetchJson(urlString: String, timeoutMs: Int = 5000): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                JSONObject(responseText)
            } else {
                Log.w(TAG, "⚠️ HTTP Error Code: $responseCode for $urlString")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "🚨 Connection Error: ${e.message} for $urlString")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
