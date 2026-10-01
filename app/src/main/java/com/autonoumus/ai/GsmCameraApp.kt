package com.autonoumus.ai

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GsmCameraApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoader: ImageLoader

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Debug builds only: log main-thread disk/network access (ANR risks) and leaked resources.
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                    .detectNetwork().detectCustomSlowCalls().penaltyLog().build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder().detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects().detectActivityLeaks().penaltyLog().build()
            )
        }
        // Load the app's SharedPreferences files into memory on a background thread. The first
        // getSharedPreferences() of a file reads it from disk on the calling thread - measured
        // 24-65 ms of main-thread disk I/O (StrictMode) when map/trip screens first touched them.
        Thread({
            for (name in arrayOf(
                "gsm_camera_prefs", "map_camera", "step_speed", "osmdroid_prefs",
                "gsm_saved_location_templates_prefs", "gsm_trip_history_prefs"
            )) {
                runCatching { getSharedPreferences(name, MODE_PRIVATE).all }
            }
        }, "prefs-preload").start()
        // Lock application to Light theme so system night mode never overrides app designs
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        // Pre-initialize OSMDroid & High-Speed Tile Caching on Application start
        com.autonoumus.ai.core.util.FastTileSources.optimizeConfiguration(this)
        // Falls back to keyless maps for this session if Esri refuses the key (quota/expiry).
        com.autonoumus.ai.core.util.FastTileSources.checkKeyedServices()
    }

    override fun newImageLoader(): ImageLoader = imageLoader
}
