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
