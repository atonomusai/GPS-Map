import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

// Windy Webcams API key for the Live Street Camera feature (free tier: https://api.windy.com/keys).
// Add `WINDY_API_KEY=your_key_here` to local.properties (gitignored) — never commit a real key.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val windyApiKey: String = localProperties.getProperty("WINDY_API_KEY", "")

// Optional map keys (free tiers, commercial use allowed). Empty = keyless EOX maps are used.
//   ARCGIS_API_KEY - ArcGIS Location Platform (location.arcgis.com), "Basemaps" privilege: sharp satellite.
//   CARTO_API_KEY  - CARTO basemaps key: CDN street map + hybrid labels.
val arcgisApiKey: String = localProperties.getProperty("ARCGIS_API_KEY", "")
val cartoApiKey: String = localProperties.getProperty("CARTO_API_KEY", "")

// Contact / legal links shown in the app. SUPPORT_EMAIL + PRIVACY_POLICY_URL are required for
// release builds (see check below); TERMS_URL is optional (the Settings row hides while empty).
val supportEmail: String = localProperties.getProperty("SUPPORT_EMAIL", "")
val privacyPolicyUrl: String = localProperties.getProperty("PRIVACY_POLICY_URL", "")
val termsUrl: String = localProperties.getProperty("TERMS_URL", "")

android {
    namespace = "com.autonoumus.ai"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.gpsmapcamera.gpslocation.timestamp.geotag"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        androidResources {
            localeFilters += listOf("en")
        }

        buildConfigField("String", "WINDY_API_KEY", "\"$windyApiKey\"")
        buildConfigField("String", "ARCGIS_API_KEY", "\"$arcgisApiKey\"")
        buildConfigField("String", "CARTO_API_KEY", "\"$cartoApiKey\"")
        buildConfigField("String", "SUPPORT_EMAIL", "\"$supportEmail\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyPolicyUrl\"")
        buildConfigField("String", "TERMS_URL", "\"$termsUrl\"")
    }

    // Upload key for Google Play (Play App Signing re-signs for users). Read from keystore.properties
    // (gitignored) so passwords never live in source; without it the release is built unsigned.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// A Play release must not ship with an empty support e-mail or privacy policy link.
// (Hooked on packaging so lint / R8 still run; only producing the release APK/AAB is blocked.)
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }.configureEach {
    doFirst {
        val missing = listOf(
            "SUPPORT_EMAIL" to supportEmail,
            "PRIVACY_POLICY_URL" to privacyPolicyUrl
        ).filter { it.second.isBlank() }.map { it.first }
        if (missing.isNotEmpty()) {
            throw GradleException("Release build blocked: set ${missing.joinToString()} in local.properties")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)

    // Lifecycle & Coroutines
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.effects)

    // Media / EXIF
    implementation(libs.androidx.exifinterface)

    // Image loading
    implementation(libs.coil)

    // Fragment KTX (activityViewModels, setFragmentResult)
    implementation(libs.androidx.fragment.ktx)

    // OpenStreetMap (OSM)
    implementation(libs.osmdroid.android)

    // Media3 Video Transformation & Overlays
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)

    // Firebase (BoM, Crashlytics, Analytics)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}