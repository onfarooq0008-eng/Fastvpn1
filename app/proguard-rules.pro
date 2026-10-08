# WireGuard / Jni
-keep class com.wireguard.** { *; }
-keepclassmembers class com.wireguard.** { *; }
# Gson models
-keep class com.fastvpnn.app.data.** { *; }
-keepattributes Signature

# Meta Audience Network (consumer rules ship with the SDK; this only silences optional-annotation warnings)
-dontwarn com.facebook.infer.annotation.**
