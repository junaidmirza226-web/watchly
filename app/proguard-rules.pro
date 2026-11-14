# ==============================================================================
# Watchly ProGuard / R8 Obfuscation & Security Rules
# Protects against reverse engineering, decompilation, and code inspection.
# ==============================================================================

# Aggressive name obfuscation and class repackaging
-repackageclasses 'com.watchly.app.internal'
-allowaccessmodification
-overloadaggressively

# Strip all debugging symbols, source file names, and line numbers in release
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Strip Android logging and diagnostics in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Keep Android components specified in AndroidManifest.xml
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep Media3 / ExoPlayer reflection requirements
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.ui.** { *; }

# Keep Coil image loader
-keep class coil.** { *; }

# Keep Kotlin Coroutines & Flow reflection rules
-keepclassmembers class kotlinx.coroutines.** { *; }

# Keep data models used in serialization or state persistence
-keepclassmembers class com.watchly.app.data.model.** {
    <fields>;
}
-keepclassmembers class com.watchly.app.data.remote.config.** {
    <fields>;
}
