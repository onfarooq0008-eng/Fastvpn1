# WireGuard / Jni
-keep class com.wireguard.** { *; }
-keepclassmembers class com.wireguard.** { *; }
# Data models (kept as-is: they are written/read by name)
-keep class com.fastvpnn.app.data.** { *; }
-keepattributes Signature

# Readable crash reports in Play Console (line numbers survive, original file names are hidden)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Release builds don't need verbose/debug logging (warnings and errors are kept for crash diagnosis).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Unity Ads: keep what the SDK loads by reflection. This block existed in the working project and was
# dropped in the rewrite; debug (no R8) works either way, release (R8 full mode) needs it.
# (Unity Ads 4.20+ ships similar consumer rules; kept here as a safety net.)
-keep class com.unity3d.ads.** { *; }
-keep class com.unity3d.services.** { *; }
-keep class com.unity3d.scar.adapter.** { *; }
-dontwarn com.unity3d.**

# Unity Ads uses WorkManager for its event delivery; R8 full mode (AGP 9) can strip these reflective classes.
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger {
    <init>();
}
