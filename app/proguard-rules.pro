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
