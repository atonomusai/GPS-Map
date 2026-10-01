package com.autonoumus.ai.core.util

import android.content.Context
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.util.Log
import android.view.MotionEvent
import com.autonoumus.ai.BuildConfig
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.util.SimpleInvalidationHandler
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView

/**
 * Map tile sources + osmdroid performance configuration.
 *
 * Every source here is free AND licensed for use in a commercial Play Store app. (The previous
 * Google mt0-mt3 tile URLs were free to call but not licensed - Google's terms forbid using them
 * outside the official Maps SDK.)
 *
 * - Street / Terrain / Satellite fallback / Hybrid labels: EOX::Maps (maps.eox.at) - no key,
 *   commercial use allowed with attribution; rate-limited, no SLA.
 * - Satellite (sharp): Esri World Imagery when ARCGIS_API_KEY is set in local.properties
 *   (ArcGIS Location Platform free tier: 2M tiles/month, commercial licence). Without a key it
 *   falls back to EOX Sentinel-2 cloudless 2016 (CC BY 4.0; ~10 m/pixel, soft at street zoom).
 * - Street + hybrid labels (reliable CDN): CARTO when CARTO_API_KEY is set (free key, 1M
 *   tiles/month commercial). Without a key EOX is used.
 *
 * All sources are 256 px so they can be layered (osmdroid's tile size is process-global).
 * Screens must switch maps with [setBaseMap] (not MapView.setTileSource) so the hybrid label
 * layer and the required attribution line follow the selected map.
 */
object FastTileSources {

    private val ESRI_KEY: String = BuildConfig.ARCGIS_API_KEY
    private val CARTO_KEY: String = BuildConfig.CARTO_API_KEY

    private val esriKeyConfigured: Boolean = ESRI_KEY.isNotBlank()

    /**
     * False once Esri has rejected the key (monthly free quota used up, key expired/revoked).
     * Checked at app start by [checkKeyedServices]; the maps then fall back to the keyless EOX
     * sources instead of showing blank satellite tiles to every user.
     */
    @Volatile
    private var esriAccepted = true

    val hasEsriKey: Boolean get() = esriKeyConfigured && esriAccepted
    val hasCartoKey: Boolean get() = CARTO_KEY.isNotBlank()

    // EOX: ~0.8 s per tile from its single server, so parallelism is what makes a screen fill fast
    // (measured on a Pixel 6a: 4 connections -> ~3 s cold satellite screen). EOX's terms allow app
    // use (rate-limited, no SLA) and don't forbid look-ahead, so viewport-adjacent prefetch is on;
    // bulk/offline area downloads stay off.
    private val COMMUNITY_POLICY = TileSourcePolicy(
        8,
        TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
    )
    // Keyed CDN services (Esri, CARTO): prefetch allowed, counts against the free monthly quota.
    private val KEYED_POLICY = TileSourcePolicy(10, TileSourcePolicy.FLAG_NO_BULK)

    private const val EOX_BASE = "https://tiles.maps.eox.at/wmts/1.0.0/"
    private const val EOX_LINK = "EOX maps.eox.at"

    /** Generic XYZ source; [url] builds the tile URL from z/x/y. */
    private class XyzTileSource(
        name: String,
        maxZoom: Int,
        ending: String,
        baseUrl: String,
        copyright: String,
        policy: TileSourcePolicy,
        private val url: (z: Int, x: Int, y: Int) -> String
    ) : OnlineTileSourceBase(name, 0, maxZoom, 256, ending, arrayOf(baseUrl), copyright, policy) {
        override fun getTileURLString(pMapTileIndex: Long): String = url(
            MapTileIndex.getZoom(pMapTileIndex),
            MapTileIndex.getX(pMapTileIndex),
            MapTileIndex.getY(pMapTileIndex)
        )
    }

    /** One tile layer drawn above the base, optionally recoloured (e.g. made translucent). */
    class Layer(val source: OnlineTileSourceBase, val filter: ColorFilter? = null)

    /**
     * A base map with extra tile layers on top: satellite + roads + labels (Hybrid) or streets +
     * relief shading (Terrain). Used directly as a tile source it shows the base; [setBaseMap]
     * adds the [layers], applies [baseFilter] to the base tiles and [tilesScale] to the map.
     */
    class HybridTileSource internal constructor(
        val imagery: OnlineTileSourceBase,
        val layers: List<Layer>,
        name: String,
        copyright: String,
        val baseFilter: ColorFilter? = null,
        val tilesScale: Float = 1f
    ) : OnlineTileSourceBase(
        name, imagery.minimumZoomLevel, imagery.maximumZoomLevel, 256,
        imagery.imageFilenameEnding(), arrayOf(imagery.baseUrl), copyright, imagery.tileSourcePolicy
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String = imagery.getTileURLString(pMapTileIndex)
    }

    /** Multiplies a layer's alpha, keeping its colours (translucent overlays). */
    private fun alphaFilter(alpha: Float): ColorFilter = ColorMatrixColorFilter(
        ColorMatrix().apply { setScale(1f, 1f, 1f, alpha) }
    )

    private fun cartoSub(x: Int, y: Int) = "abcd"[Math.floorMod(x + y, 4)]

    // ---- Street ----
    private val EOX_STREET: OnlineTileSourceBase = XyzTileSource(
        "EoxOsm", 18, ".jpg", EOX_BASE,
        "© OpenStreetMap contributors, © $EOX_LINK", COMMUNITY_POLICY
    ) { z, x, y -> "${EOX_BASE}osm_3857/default/g/$z/$y/$x.jpg" }

    private val CARTO_STREET: OnlineTileSourceBase = XyzTileSource(
        "CartoVoyagerKey", 20, ".png", "https://basemaps.cartocdn.com/",
        "© OpenStreetMap contributors © CARTO", KEYED_POLICY
    ) { z, x, y ->
        "https://${cartoSub(x, y)}.basemaps.cartocdn.com/rastertiles/voyager/$z/$x/$y@2x.png?key=$CARTO_KEY"
    }

    // ---- Terrain ----
    private val EOX_TERRAIN: OnlineTileSourceBase = XyzTileSource(
        "EoxTerrain", 18, ".jpg", EOX_BASE,
        "© OpenStreetMap contributors & others, © $EOX_LINK", COMMUNITY_POLICY
    ) { z, x, y -> "${EOX_BASE}terrain_3857/default/g/$z/$y/$x.jpg" }

    // Esri World Hillshade (relief only, grey). ~0.4 s/tile from Esri's CDN vs ~1 s for EOX terrain.
    // Relief has no extra detail past ~z16, so higher zooms are scaled on the phone.
    private val ESRI_HILLSHADE: OnlineTileSourceBase = XyzTileSource(
        "EsriWorldHillshade", 16, ".jpg", "https://ibasemaps-api.arcgis.com/",
        "Relief: Powered by Esri", KEYED_POLICY
    ) { z, x, y ->
        "https://ibasemaps-api.arcgis.com/arcgis/rest/services/Elevation/World_Hillshade/MapServer/tile/$z/$y/$x?token=$ESRI_KEY"
    }

    /**
     * Turns the grey hillshade into a see-through shadow layer: flat ground (grey ~250) becomes fully
     * transparent, slopes (grey ~100-200) become a warm shadow, so the map shows through like
     * Google's Terrain view. alpha = 1.25 * (250 - grey), colour a fixed dark brown.
     */
    private val RELIEF_SHADOW_FILTER: ColorFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 70f,
                0f, 0f, 0f, 0f, 55f,
                0f, 0f, 0f, 0f, 40f,
                -0.4167f, -0.4167f, -0.4167f, 0f, 312.5f
            )
        )
    )

    /**
     * Terrain palette for the street tiles: land turns a light green-beige (cream #FAF8F2 ->
     * ~#E8F6CE), like Google's Terrain view, so Terrain looks different from Street even on flat
     * ground where there is no relief to shade.
     */
    private val TERRAIN_PALETTE_FILTER: ColorFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.93f, 0f, 0f, 0f, 0f,
                0f, 0.97f, 0f, 0f, 6f,
                0f, 0f, 0.85f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    /**
     * Road network only (transparent PNG, OSM data, z0-18) - drawn translucent over the imagery
     * for Hybrid so it reads like Google's hybrid roads instead of just a few place names.
     */
    private val EOX_ROADS: OnlineTileSourceBase = XyzTileSource(
        "EoxRoads", 18, ".png", EOX_BASE, "© OpenStreetMap, © EOX", COMMUNITY_POLICY
    ) { z, x, y -> "${EOX_BASE}streets_3857/default/g/$z/$y/$x.png" }

    // ---- Satellite ----
    private val ESRI_IMAGERY: OnlineTileSourceBase = XyzTileSource(
        "EsriWorldImagery", 19, ".jpg", "https://ibasemaps-api.arcgis.com/",
        "Powered by Esri | Esri, Maxar, Earthstar Geographics, and the GIS User Community", KEYED_POLICY
    ) { z, x, y ->
        "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/$z/$y/$x?token=$ESRI_KEY"
    }

    // Sentinel-2 is 10 m/pixel (native ~z14): tiles above z16 are only server-side upscales, so
    // cap at 16 and let osmdroid scale z16 on the phone (screens allow zoom 20) - no extra downloads.
    private val EOX_SENTINEL_2016: OnlineTileSourceBase = XyzTileSource(
        "EoxS2Cloudless2016", 16, ".jpg", EOX_BASE,
        "Sentinel-2 cloudless s2maps.eu by EOX (modified Copernicus Sentinel data 2016)",
        COMMUNITY_POLICY
    ) { z, x, y -> "${EOX_BASE}s2cloudless_3857/default/g/$z/$y/$x.jpg" }

    // ---- Labels for hybrid ----
    // EOX's place-name overlay only goes to z14; its streets overlay covers z15-18.
    private val EOX_LABELS: OnlineTileSourceBase = XyzTileSource(
        "EoxLabels", 18, ".png", EOX_BASE,
        "© OpenStreetMap, © EOX", COMMUNITY_POLICY
    ) { z, x, y ->
        val layer = if (z <= 14) "overlay_bright_3857" else "streets_3857"
        "$EOX_BASE$layer/default/g/$z/$y/$x.png"
    }

    private val CARTO_LABELS: OnlineTileSourceBase = XyzTileSource(
        "CartoLabelsKey", 20, ".png", "https://basemaps.cartocdn.com/",
        "© OpenStreetMap contributors © CARTO", KEYED_POLICY
    ) { z, x, y ->
        "https://${cartoSub(x, y)}.basemaps.cartocdn.com/rastertiles/voyager_only_labels/$z/$x/$y@2x.png?key=$CARTO_KEY"
    }

    /** Street map (default for every map screen). */
    val STREET: ITileSource = if (hasCartoKey) CARTO_STREET else EOX_STREET

    /** Satellite imagery without labels. */
    val SATELLITE: ITileSource get() = if (hasEsriKey) ESRI_IMAGERY else EOX_SENTINEL_2016

    /**
     * Imagery sharpness. osmdroid enlarges every 256 px tile ~2.6x on a high-density phone, which
     * made the satellite view soft. Drawing tiles smaller makes the map load the next zoom level's
     * imagery for the same view: 0.5 = twice the detail (4x the tiles), 0.7 for Hybrid so the
     * CARTO labels stay at their normal size. Esri only (EOX Sentinel has no more detail).
     */
    private const val SATELLITE_TILES_SCALE = 0.5f
    private const val HYBRID_TILES_SCALE = 0.7f

    private fun buildHybrid(esri: Boolean): HybridTileSource {
        val imagery = if (esri) ESRI_IMAGERY else EOX_SENTINEL_2016
        val imageryCredit = if (esri) {
            "Powered by Esri | Esri, Maxar, Earthstar Geographics, GIS User Community"
        } else {
            EOX_SENTINEL_2016.copyrightNotice
        }
        return HybridTileSource(
            imagery,
            if (hasCartoKey) {
                listOf(Layer(EOX_ROADS, alphaFilter(0.55f)), Layer(CARTO_LABELS))
            } else {
                listOf(Layer(EOX_LABELS))
            },
            "Hybrid_" + imagery.name(),
            "$imageryCredit · © OpenStreetMap, © EOX" + if (hasCartoKey) ", © CARTO" else "",
            tilesScale = if (esri) HYBRID_TILES_SCALE else 1f
        )
    }

    private val HYBRID_ESRI: HybridTileSource by lazy { buildHybrid(esri = true) }
    private val HYBRID_KEYLESS: HybridTileSource by lazy { buildHybrid(esri = false) }

    /** Satellite imagery + translucent roads + road/place labels. */
    val HYBRID: ITileSource get() = if (hasEsriKey) HYBRID_ESRI else HYBRID_KEYLESS

    /**
     * Terrain / relief map. With the Esri key: the street map in a terrain palette + Esri relief
     * shading on top (both on fast CDNs; the base shares the Street map's cache). Without it:
     * EOX terrain (slower server).
     */
    private val TERRAIN_ESRI: HybridTileSource by lazy {
        HybridTileSource(
            STREET as OnlineTileSourceBase,
            listOf(Layer(ESRI_HILLSHADE, RELIEF_SHADOW_FILTER)),
            "Terrain_" + (STREET as OnlineTileSourceBase).name(),
            "${(STREET as OnlineTileSourceBase).copyrightNotice} · Relief: Powered by Esri",
            baseFilter = TERRAIN_PALETTE_FILTER
        )
    }

    val TERRAIN: ITileSource get() = if (hasEsriKey) TERRAIN_ESRI else EOX_TERRAIN

    /**
     * One tiny Esri request at app start (background thread). If Esri refuses the key - free
     * monthly quota exhausted, key expired (keys last max. 1 year) or revoked - switch this session
     * to the keyless sources. Network errors/offline keep Esri (nothing is known yet).
     */
    fun checkKeyedServices() {
        if (!esriKeyConfigured) return
        Thread({
            try {
                val conn = java.net.URL(
                    "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/0/0/0?token=$ESRI_KEY"
                ).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", AppLinks.userAgent)
                val code = conn.responseCode
                val type = conn.contentType.orEmpty()
                // Esri answers auth/quota problems as 4xx or as a JSON error body instead of an image.
                if (code in 400..499 || (code == 200 && !type.startsWith("image"))) {
                    esriAccepted = false
                    Log.w("FastTileSources", "Esri refused the key (HTTP $code, $type) - using keyless maps")
                }
                conn.disconnect()
            } catch (e: Exception) {
                // Offline / timeout: keep Esri; tiles will simply load when the network is back.
            }
        }, "esri-key-check").start()
    }

    private fun tilesScaleFor(source: ITileSource): Float = when {
        source is HybridTileSource -> source.tilesScale
        source === ESRI_IMAGERY -> SATELLITE_TILES_SCALE
        else -> 1f
    }

    /** Label layer drawn above the base tiles for [HybridTileSource]. */
    private class LabelsOverlay(provider: MapTileProviderBasic, context: Context) : TilesOverlay(provider, context, true, false) {
        init {
            loadingBackgroundColor = Color.TRANSPARENT
            loadingLineColor = Color.TRANSPARENT
        }
    }

    /** Attribution line required by every tile provider's terms. */
    // (osmdroid's CopyrightOverlay re-reads only the base layer's credit every frame and can't
    // shrink long text, so the overlay layer's credit got dropped / the line ran off screen.)
    private class AttributionOverlay(context: Context) : Overlay() {
        var notice: String = ""
        private val density = context.resources.displayMetrics.density
        private val maxTextPx = 9f * context.resources.displayMetrics.scaledDensity
        private val pad = 3f * density
        private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC1F2937")
        }
        private val bgPaint = android.graphics.Paint().apply { color = Color.parseColor("#B3FFFFFF") }

        override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow || notice.isEmpty()) return
            val available = mapView.width - 2 * pad
            textPaint.textSize = maxTextPx
            val w = textPaint.measureText(notice)
            if (w > available) textPaint.textSize = maxTextPx * available / w // shrink to one line
            val textW = textPaint.measureText(notice)
            val fm = textPaint.fontMetrics
            val bottom = mapView.height.toFloat()
            val top = bottom - (fm.descent - fm.ascent) - 2 * pad
            // Screen-fixed: undo the rotation / pinch-scale osmdroid applies to overlays (same as
            // osmdroid's own CopyrightOverlay does).
            val projection = mapView.projection
            projection.save(canvas, false, false)
            canvas.drawRect(0f, top, textW + 2 * pad, bottom, bgPaint)
            canvas.drawText(notice, pad, bottom - pad - fm.descent, textPaint)
            projection.restore(canvas, false)
        }
    }

    /**
     * Moves the camera like Google Maps: a short animation for nearby targets, but an instant jump
     * for far ones. osmdroid's animateTo requests tiles for every intermediate frame, so flying
     * 200 km (a search result, "my location") queued dozens of useless downloads and the
     * destination stayed grey for ~4 s (measured on a Pixel 6a).
     */
    fun flyTo(mapView: MapView, target: org.osmdroid.api.IGeoPoint, zoom: Double? = null, durationMs: Long = 500L) {
        val current = mapView.mapCenter
        val result = FloatArray(1)
        android.location.Location.distanceBetween(
            current.latitude, current.longitude, target.latitude, target.longitude, result
        )
        // "Far" = more than ~1.5 screen widths at the destination zoom.
        val z = zoom ?: mapView.zoomLevelDouble
        val metersPerPixel = 156543.03392 * Math.cos(Math.toRadians(target.latitude)) / Math.pow(2.0, z)
        val screenMeters = metersPerPixel * mapView.width.coerceAtLeast(1) /
            mapView.context.resources.displayMetrics.density
        if (result[0] > screenMeters * 1.5 || mapView.width == 0) {
            if (zoom != null) mapView.controller.setZoom(zoom)
            mapView.controller.setCenter(target)
        } else if (zoom != null) {
            mapView.controller.animateTo(target, zoom, durationMs)
        } else {
            mapView.controller.animateTo(target)
        }
    }

    /** True for the overlays [setBaseMap] manages; screens that clear overlays should keep them. */
    fun isMapLayerOverlay(overlay: Overlay): Boolean = overlay is LabelsOverlay || overlay is AttributionOverlay

    /**
     * Shows [source] on [mapView]: sets the base tiles, adds/removes the hybrid label layer and
     * keeps the attribution line in sync. Use this instead of MapView.setTileSource.
     */
    fun setBaseMap(mapView: MapView, source: ITileSource) {
        val hybrid = source as? HybridTileSource

        // Sharper imagery (see SATELLITE_TILES_SCALE). Changing the scale changes how much ground a
        // zoom level shows, so shift the zoom to keep the same view on screen.
        val newScale = tilesScaleFor(source)
        val oldScale = mapView.tilesScaleFactor
        if (newScale != oldScale) {
            val center = mapView.mapCenter
            val zoom = mapView.zoomLevelDouble
            mapView.tilesScaleFactor = newScale
            if (mapView.width > 0) {
                val shifted = zoom + Math.log(oldScale.toDouble() / newScale) / Math.log(2.0)
                mapView.controller.setZoom(shifted.coerceIn(mapView.minZoomLevel, mapView.maxZoomLevel))
                mapView.controller.setCenter(center)
            }
        }

        mapView.setTileSource(hybrid?.imagery ?: source)
        mapView.overlayManager.tilesOverlay.setColorFilter(hybrid?.baseFilter)

        mapView.overlays.filterIsInstance<LabelsOverlay>().forEach {
            it.onDetach(mapView)
            mapView.overlays.remove(it)
        }
        // Added bottom-up at index 0 so they stay under markers/routes, in layer order.
        hybrid?.layers?.asReversed()?.forEach { layer ->
            val provider = MapTileProviderBasic(mapView.context.applicationContext, layer.source)
            provider.tileRequestCompleteHandlers.add(SimpleInvalidationHandler(mapView))
            mapView.overlays.add(0, LabelsOverlay(provider, mapView.context).apply {
                layer.filter?.let { setColorFilter(it) }
            })
        }

        val attribution = mapView.overlays.filterIsInstance<AttributionOverlay>().firstOrNull()
            ?: AttributionOverlay(mapView.context).also { mapView.overlays.add(it) }
        attribution.notice = source.copyrightNotice.orEmpty()
        mapView.invalidate()
    }

    @Volatile
    private var isConfigured = false

    /**
     * Optimizes OSMDroid global configuration for maximum download speed and tile caching.
     * Runs once per process (from Application.onCreate); later calls are no-ops. Screens must NOT
     * call Configuration.getInstance().load() themselves - load() re-reads SharedPreferences
     * (disk I/O on the main thread) and resets the tuning below back to osmdroid's defaults
     * (2 download threads, tiny memory cache), which made every map screen open slowly.
     */
    fun optimizeConfiguration(context: Context) {
        if (isConfigured) return
        synchronized(this) {
            if (isConfigured) return
            applyConfiguration(context)
            isConfigured = true
        }
    }

    private fun applyConfiguration(context: Context) {
        try {
            val config = Configuration.getInstance()
            config.load(context.applicationContext, context.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE))
            config.userAgentValue = AppLinks.userAgent
            // Increase concurrent download threads from default 2 to 12 for high parallel bandwidth
            config.tileDownloadThreads = 12.toShort()
            config.tileDownloadMaxQueueSize = 60.toShort()
            config.tileFileSystemThreads = 6.toShort()
            // Cache up to 150 tiles in memory for instant fluid panning
            config.cacheMapTileCount = 150.toShort()
            // 350 MB disk cache for offline/re-visit instantaneous rendering
            config.tileFileSystemCacheMaxBytes = 350L * 1024 * 1024
            config.tileFileSystemCacheTrimBytes = 280L * 1024 * 1024
            // 30 days extended cache retention
            config.expirationExtendedDuration = 1000L * 60 * 60 * 24 * 30
            // osmdroid animates every +/- zoom step (and animateTo) over 1000ms by default, and
            // downloads tiles for each intermediate zoom it passes through - zoom buttons felt
            // sluggish and blurry. Google Maps' steps take ~250-300ms.
            config.animationSpeedDefault = 300
            config.animationSpeedShort = 200
        } catch (e: Exception) {
            // Ignore config exception
        }
    }

    /**
     * Applies high-performance rendering parameters to an OSMDroid MapView instance.
     * Enables DPI scaling (reduces needed tiles by 4x), hardware acceleration, and smooth tile caching.
     */
    fun MapView.applyHighPerformanceSettings(enableTouch: Boolean = true) {
        setMultiTouchControls(enableTouch)
        // Scaling tiles to DPI is the single most effective optimization:
        // On modern high-DPI phones, it reduces required tile requests from ~40 tiles to only ~8-12 tiles per screen!
        setTilesScaledToDpi(true)
        isVerticalMapRepetitionEnabled = false
        isHorizontalMapRepetitionEnabled = true
        overlayManager.tilesOverlay.loadingBackgroundColor = Color.parseColor("#F3F4F6")
        overlayManager.tilesOverlay.loadingLineColor = Color.TRANSPARENT
        // No View.LAYER_TYPE_HARDWARE here: the window is already GPU-accelerated, and a hardware
        // layer on a view that invalidates on every arriving tile / pan frame must be re-rendered
        // each time into an extra full-screen texture - more work and memory, not less.

        // Never draw the first frames at (0,0): that is open ocean, i.e. the blue (Street) or black
        // (Satellite) screen maps showed until the screen found a location. Screens that know
        // better still override this right after.
        val center = mapCenter
        if (MapCameraStore.isUnset(center.latitude, center.longitude)) {
            val camera = MapCameraStore.initialCamera(context)
            controller.setZoom(camera.zoom)
            controller.setCenter(camera.center)
        }

        if (overlays.none { it is UserGestureOverlay }) {
            overlays.add(UserGestureOverlay())
            // Once the map settles: remember the camera (next map opens right here, from cache)
            // and preload the neighbouring zoom levels so pinch / +/- is instant.
            addMapListener(DelayedMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean = onCameraIdle()
                override fun onZoom(event: ZoomEvent?): Boolean = onCameraIdle()
            }, 700L))
        }
    }

    private fun MapView.onCameraIdle(): Boolean {
        if (width == 0 || height == 0) return false
        MapCameraStore.save(context, mapCenter, zoomLevelDouble)
        MapTilePrefetcher.prefetchAdjacentZooms(this)
        return false
    }

    /**
     * True once the user has touched the map (pan / pinch / tap). Screens that follow the live
     * location must stop re-centering and re-zooming after that - several did it on every GPS
     * update (every few seconds), snapping the user's own zoom back to 15.
     */
    fun MapView.hasUserTouchedMap(): Boolean =
        overlays.any { it is UserGestureOverlay && it.touched }

    /** Invisible overlay that only records that a touch happened; never consumes the event. */
    private class UserGestureOverlay : Overlay() {
        @Volatile var touched = false
        override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touched = true
            return false
        }
    }
}
