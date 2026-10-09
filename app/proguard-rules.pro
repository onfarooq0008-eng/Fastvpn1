# WireGuard / Jni
-keep class com.wireguard.** { *; }
-keepclassmembers class com.wireguard.** { *; }
# Data models (kept as-is: they are written/read by name)
-keep class com.fastvpnn.app.data.** { *; }
-keepattributes Signature

# Readable crash reports in Play Console (line numbers survive, original file names are hidden)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Meta Audience Network (consumer rules ship with the SDK; this only silences optional-annotation warnings)
-dontwarn com.facebook.infer.annotation.**

# Ad SDKs: keep everything they load by reflection (their own consumer rules cover most of this; belt and braces for release)
-keep class com.facebook.ads.** { *; }
-keep class com.unity3d.ads.** { *; }
-keep class com.unity3d.services.** { *; }
-keep class com.unity3d.scar.adapter.** { *; }
-dontwarn com.facebook.ads.**
-dontwarn com.unity3d.**

# Unity Ads uses WorkManager for its event delivery; R8 full mode (AGP 9) can strip these reflective classes.
# (Unity Ads 4.20+ ships the same rules; kept here as a safety net.)
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger {
    <init>();
}
