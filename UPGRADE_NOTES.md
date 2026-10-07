# v1.0.4 upgrade notes

## Play Console recommendations
- **Optimized resource shrinking** -> AGP 9.0.1 (Gradle 9.1.0). `android.r8.optimizedResourceShrinking=true`
  is also set in gradle.properties. AGP 9 has built-in Kotlin, so the `org.jetbrains.kotlin.android`
  plugin was removed; the Kotlin compiler version stays 2.4.0 via the classpath line in the root build.gradle.
- **BitmapFactory without downsampling (`j0.c.c`)** -> there is no BitmapFactory call in this project's own
  code (and the new UI is vectors + Canvas only). `j0.c.c` is an R8-obfuscated class inside a dependency.
  Find which one: open `app/build/outputs/mapping/release/mapping.txt` (or the mapping file uploaded to
  Play Console) and search for `j0.c.c`. Then update that library (most likely `play-services-ads`).
- Version: versionCode 4 / versionName 1.0.4 (raise versionCode if 4 was already uploaded).

## UI
- New dark design: Home (power ring), Locations (tabs, favorites, signal bars), Settings, bottom navigation.
- "Go Premium" crown/banner exist but are hidden (`SHOW_PREMIUM` in app/build.gradle) because the app has no paid tier.
- The old Light/Dark/System theme option was removed (the design is dark-only).
- "Streaming" tab appears only if the backend sends `"streaming": true` on servers.
- Kill Switch opens Android's VPN settings (an app cannot enable lockdown mode itself).
