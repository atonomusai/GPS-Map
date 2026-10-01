# ProGuard Rules for GSMCAMER Release Build Optimization

# Preserve Line Numbers for Crash Reports
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Domain & Data Models (JSON / Serialized data)
-keep class com.autonoumus.ai.domain.model.** { *; }
-keep class com.autonoumus.ai.data.model.** { *; }
-keep class com.autonoumus.ai.core.stamp.** { *; }
-keep class com.autonoumus.ai.core.util.** { *; }

# Dagger / Hilt
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class * extends dagger.hilt.android.internal.managers.ActivityComponentManager$ActivityComponentBuilderEntryPoint { *; }
-keepclassmembers class * {
    @javax.inject.Inject *;
    @dagger.hilt.android.qualifiers.ApplicationContext *;
}

# OSMDroid (OpenStreetMap)
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Coil Image Loader
-keep class coil.** { *; }
-dontwarn coil.**

# CameraX & Media3
-keep class androidx.camera.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn androidx.camera.**
-dontwarn androidx.media3.**

# Keep ViewBinding / DataBinding classes
-keep class com.autonoumus.ai.databinding.** { *; }
# Strip debug/verbose/info logs from release builds - several printed the user's exact coordinates.
# (Log.w / Log.e are kept for crash diagnosis.)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
