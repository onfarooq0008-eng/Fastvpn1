package com.fastvpnn.app.ads

import com.fastvpnn.app.BuildConfig

/**
 * THE one place for the Unity Ads IDs and the test/real switch.
 *
 * The only ad in this app is ONE Unity banner above the bottom navigation bar (Home screen).
 *  - Debug builds always run Unity in test mode. Release builds show real ads, unless USE_TEST_ADS
 *    is set to true for the release build type in app/build.gradle.
 *  - Unity has no separate "test placement IDs": test ads come from initialising with testMode = true.
 */
object AdConfig {
    /** Unity Dashboard > Monetization > your app > Game ID. */
    const val GAME_ID = "800391481"

    /** Unity Dashboard > Monetization > Ad units > Banner placement. */
    const val BANNER_PLACEMENT_ID = "BP_Banner_Android"

    /** true => test ads only. */
    val isTestMode: Boolean get() = BuildConfig.DEBUG || BuildConfig.USE_TEST_ADS

    /** A banner that got "no fill" is requested again after this long (doubling), at most this many times. */
    const val RETRY_BASE_MS = 60_000L
    const val RETRY_MAX_TRIES = 5
}
