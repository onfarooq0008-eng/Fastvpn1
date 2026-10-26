# ---------------------------------------------------------------------------------------------
# R8 rules. Keep this file SMALL: every broad "-keep ... { *; }" stops R8 from shrinking,
# optimizing and renaming those classes, and Play Console reports it as a low optimization /
# obfuscation / shrinking rate. Only add a rule when something is really looked up by name.
# ---------------------------------------------------------------------------------------------

# WireGuard: libwg-go.so binds to the native methods of GoBackend by name (JNI). Only that class's
# native methods need protecting -- the rest of the library is ordinary Java that R8 may shrink,
# optimize and rename. (The default Android rules already do this; stated here so it's explicit.)
# The VpnService subclass the library declares in its manifest is kept automatically from the manifest.
-keepclasseswithmembernames,includedescriptorclasses class com.wireguard.android.backend.GoBackend {
    native <methods>;
}

# The app's own data classes need no rule: JSON is parsed by hand (org.json) with string keys, and
# nothing uses reflection or serialization libraries.

# Generic signatures (used by Kotlin/coroutines/OkHttp internals).
-keepattributes Signature

# Readable crash reports in Play Console (line numbers survive, original file names are hidden)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# (-repackageclasses / -allowaccessmodification were removed: they break ad SDKs that use reflection or
# package-private access, which made the signed release build crash on launch.)

# Release builds don't need verbose/debug logging (warnings and errors are kept for crash diagnosis).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Unity Ads is excluded from the build (see app/build.gradle), but the all-in-one Yandex mediation bundle may still
# reference its classes from an unused adapter. Never loaded at runtime; this only silences the R8 warning.
-dontwarn com.unity3d.**

# Yandex Mediation partner adapters (all-in-one bundle): they reference optional classes of networks that are
# not always present. Missing optional classes must not fail the R8 release / bundle build.
-dontwarn com.yandex.mobile.ads.mediation.**
-dontwarn com.applovin.**
-dontwarn com.google.android.gms.ads.**
-dontwarn com.startapp.**
-dontwarn com.ironsource.**
-dontwarn com.unity3d.**
-dontwarn com.pangle.**
-dontwarn com.bytedance.**
-dontwarn com.bigosdk.**
-dontwarn sg.bigo.**
-dontwarn com.moloco.**
-dontwarn io.github.maticooads.**
-dontwarn com.mbridge.**
-dontwarn com.chartboost.**
-dontwarn com.tapjoy.**
-dontwarn com.vungle.**
-dontwarn com.my.target.**
-dontwarn com.inmobi.**
-dontwarn com.fyber.**
-dontwarn org.chromium.**
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**

# Yandex Mediation loads its partner adapters and their networks BY NAME (reflection), so renaming/removing them
# makes the release app crash or ads silently never load. Debug builds are not minified, which is why only release broke.
-keep class com.yandex.mobile.ads.** { *; }
-keep class com.yandex.mobile.ads.mediation.** { *; }
-keep class com.google.android.gms.ads.** { *; }
-keep class com.startapp.** { *; }
-keep class com.applovin.** { *; }
-keep class com.ironsource.** { *; }
-keep class com.pangle.** { *; }
-keep class com.bytedance.sdk.** { *; }
-keep class com.mbridge.** { *; }
-keep class com.chartboost.** { *; }
-keep class com.tapjoy.** { *; }
-keep class com.bigosdk.** { *; }
-keep class sg.bigo.** { *; }
-keep class com.moloco.** { *; }
-keep class io.github.maticooads.** { *; }
-keep class com.vungle.** { *; }
-keep class com.my.target.** { *; }
-keep class com.inmobi.** { *; }
-keep class com.fyber.** { *; }
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Exceptions
