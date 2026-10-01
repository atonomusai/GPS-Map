package com.autonoumus.ai.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Downloads map tiles into osmdroid's disk cache *before* a map needs them, so maps draw from
 * cache like Google Maps instead of showing grey squares that fill in one by one.
 *
 * - [warmUp]: the area around the user at the zoom levels map screens open with. Run on app
 *   start / home screen, so the first map screen of the session already has its tiles.
 * - [prefetchAdjacentZooms]: when a map stops moving, the next zoom level in and out for what is
 *   on screen - pinch or +/- then shows sharp tiles immediately instead of a blurry upscale that
 *   takes 1-2s per tile to replace (measured Google tile latency on a cold connection).
 *
 * Only missing/expired tiles are fetched, at most 2 at a time so the visible map's own downloads
 * (12 threads) always win, and a viewport request drops whatever is still queued from an earlier
 * one so it never works on an area the user already left.
 */
object MapTilePrefetcher {

    private const val TAG = "MapTilePrefetcher"
    private const val MAX_TILES_PER_REQUEST = 48

    private val executor = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
        allowCoreThreadTimeOut(true)
    }
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val viewportGeneration = AtomicInteger()
    private val downloader by lazy { TileDownloader() }
    // Same SQLite cache file the MapViews read (osmdroid's own CacheManager uses it the same way).
    private val writer by lazy { SqlTileWriter() }

    @Volatile private var lastWarmUpLat = Double.NaN
    @Volatile private var lastWarmUpLon = Double.NaN
    @Volatile private var lastWarmUpMs = 0L

    /** Warm the cache around the user's cached position. Cheap to call often (throttled, skips cached tiles). */
    fun warmUp(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                val loc = MapCameraStore.lastKnownLocation(appContext)
                val center = loc?.let { it.latitude to it.longitude }
                    ?: MapCameraStore.savedCamera(appContext)?.let { it.center.latitude to it.center.longitude }
                    ?: run {
                        if (com.autonoumus.ai.BuildConfig.DEBUG) Log.d(TAG, "warmUp: no location yet")
                        return@execute
                    }
                if (!shouldWarmUp(center.first, center.second)) return@execute

                val tiles = ArrayList<Pair<ITileSource, Long>>()
                // Street: the zooms screens open at (15-16.5) plus one level either side.
                if (allowsPrefetch(FastTileSources.STREET)) {
                    for (z in 14..16) tiles += tilesAround(FastTileSources.STREET, center.first, center.second, z)
                }
                // Satellite imagery is ~3x heavier: only on Wi-Fi / unmetered networks.
                if (!isMetered(appContext) && allowsPrefetch(FastTileSources.SATELLITE)) {
                    for (z in 14..16) tiles += tilesAround(FastTileSources.SATELLITE, center.first, center.second, z)
                }
                if (tiles.isEmpty()) return@execute
                val results = tiles.map { (source, index) -> fetchIfMissing(source, index, generation = null) }
                if (com.autonoumus.ai.BuildConfig.DEBUG) {
                    Log.d(TAG, "warmUp: ${tiles.size} tiles, downloaded=${results.count { it == Result.DOWNLOADED }} " +
                        "cached=${results.count { it == Result.CACHED }} failed=${results.count { it == Result.FAILED }}")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "warmUp failed: ${e.message}")
            }
        }
    }

    /** Pre-download the zoom level above and below the visible area of [mapView]. */
    fun prefetchAdjacentZooms(mapView: MapView) {
        val source = mapView.tileProvider?.tileSource as? OnlineTileSourceBase ?: return
        if (!allowsPrefetch(source)) return
        val box = mapView.boundingBox ?: return
        val zoom = mapView.zoomLevelDouble.roundToInt()
        val metered = isMetered(mapView.context)
        val generation = viewportGeneration.incrementAndGet()

        val tiles = ArrayList<Long>()
        // 1) Same zoom, one screen-width ring around the viewport, nearest first - panning then
        //    reveals cached tiles instead of grey squares (measured: 1-2 s of grey per pan before).
        if (zoom in source.minimumZoomLevel..source.maximumZoomLevel) {
            val center = MapTileIndex.getTileIndex(
                zoom,
                MapView.getTileSystem().getTileXFromLongitude(box.centerLongitude, zoom),
                MapView.getTileSystem().getTileYFromLatitude(box.centerLatitude, zoom)
            )
            tiles += CacheManager.getTilesCoverage(box.increaseByScale(if (metered) 1.6f else 2.2f), zoom)
                .sortedBy { tileDistance(it, center) }
        }
        // 2) One level out (a quarter of the tiles) so zooming out is instant.
        if (zoom - 1 >= source.minimumZoomLevel) tiles += CacheManager.getTilesCoverage(box, zoom - 1)
        // 3) One level in covers 4x the tiles; on mobile data only the central part of the screen.
        if (zoom + 1 <= source.maximumZoomLevel) {
            val zoomInBox = if (metered) box.increaseByScale(0.5f) else box
            tiles += CacheManager.getTilesCoverage(zoomInBox, zoom + 1)
        }
        tiles.distinct().take(MAX_TILES_PER_REQUEST).forEach { index ->
            executor.execute { fetchIfMissing(source, index, generation) }
        }
    }

    /**
     * Free community tile servers (EOX, OpenStreetMap) forbid pre-emptive downloads and block apps
     * that do it; only keyed sources (Esri, CARTO) allow it, and there it counts against the quota.
     */
    private fun allowsPrefetch(source: ITileSource): Boolean =
        (source as? OnlineTileSourceBase)?.tileSourcePolicy?.acceptsPreventive() == true

    private fun tileDistance(a: Long, b: Long): Int {
        val dx = MapTileIndex.getX(a) - MapTileIndex.getX(b)
        val dy = MapTileIndex.getY(a) - MapTileIndex.getY(b)
        return dx * dx + dy * dy
    }

    private enum class Result { CACHED, DOWNLOADED, FAILED, SKIPPED }

    private fun fetchIfMissing(source: ITileSource, index: Long, generation: Int?): Result {
        if (generation != null && generation != viewportGeneration.get()) return Result.SKIPPED // area already left
        val online = source as? OnlineTileSourceBase ?: return Result.SKIPPED
        val key = "${source.name()}/$index"
        if (!inFlight.add(key)) return Result.SKIPPED
        return try {
            val expires = writer.getExpirationTimestamp(source, index)
            if (expires != null && expires > System.currentTimeMillis()) {
                Result.CACHED
            } else if (downloader.downloadTile(index, writer, online) != null) {
                Result.DOWNLOADED
            } else {
                Result.FAILED
            }
        } catch (e: Throwable) {
            // Offline, low memory, server error: the map will simply load the tile itself later.
            Result.FAILED
        } finally {
            inFlight.remove(key)
        }
    }

    /** A screen-sized block (3 wide x 5 tall, portrait) of tiles around a point. */
    private fun tilesAround(source: ITileSource, lat: Double, lon: Double, zoom: Int): List<Pair<ITileSource, Long>> {
        if (zoom < source.minimumZoomLevel || zoom > source.maximumZoomLevel) return emptyList()
        val tileSystem = MapView.getTileSystem()
        val cx = tileSystem.getTileXFromLongitude(lon, zoom)
        val cy = tileSystem.getTileYFromLatitude(lat, zoom)
        val max = (1 shl zoom) - 1
        val result = ArrayList<Pair<ITileSource, Long>>()
        for (x in cx - 1..cx + 1) for (y in cy - 2..cy + 2) {
            if (x in 0..max && y in 0..max) result += source to MapTileIndex.getTileIndex(zoom, x, y)
        }
        return result
    }

    private fun shouldWarmUp(lat: Double, lon: Double): Boolean {
        val now = System.currentTimeMillis()
        if (!lastWarmUpLat.isNaN()) {
            val moved = FloatArray(1)
            android.location.Location.distanceBetween(lastWarmUpLat, lastWarmUpLon, lat, lon, moved)
            if (moved[0] < 1_000f && now - lastWarmUpMs < 30 * 60_000L) return false
        }
        lastWarmUpLat = lat
        lastWarmUpLon = lon
        lastWarmUpMs = now
        return true
    }

    private fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return try { cm.isActiveNetworkMetered } catch (e: Exception) { true }
    }
}
