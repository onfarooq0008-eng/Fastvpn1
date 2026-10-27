package com.fastvpnn.app.ads

import com.fastvpnn.app.BuildConfig

/**
 * THE one place for the ad settings.
 *
 * Ads in this app (all served by the Yandex Mobile Ads SDK -- the only ad SDK in the app, no mediation):
 *  - BANNER        : one adaptive banner above the bottom navigation bar.
 *  - NATIVE        : one card on the Home screen and one inside the Locations list.
 *  - INTERSTITIAL  : full-screen ad shown right after the user taps Disconnect.
 *
 * Test ads: debug builds always use Yandex's public demo units. Release builds use the real units below,
 * unless USE_TEST_ADS is set to true for the release build type in app/build.gradle.
 */
object AdConfig {
    // ---- real Yandex ad units ----
    private const val BANNER_AD_UNIT_ID = "R-M-20279211-1"
    private const val NATIVE_AD_UNIT_ID = "R-M-20279211-2"
    private const val INTERSTITIAL_AD_UNIT_ID = "R-M-20279211-3"

    // ---- Yandex public demo units (always fill, earn nothing) ----
    private const val DEMO_BANNER = "demo-banner-yandex"
    private const val DEMO_NATIVE = "demo-native-content-yandex"
    private const val DEMO_INTERSTITIAL = "demo-interstitial-yandex"

    /** true => demo ads only. */
    val isTestMode: Boolean get() = BuildConfig.DEBUG || BuildConfig.USE_TEST_ADS

    val bannerAdUnitId: String get() = if (isTestMode) DEMO_BANNER else BANNER_AD_UNIT_ID
    val nativeAdUnitId: String get() = if (isTestMode) DEMO_NATIVE else NATIVE_AD_UNIT_ID
    val interstitialAdUnitId: String get() = if (isTestMode) DEMO_INTERSTITIAL else INTERSTITIAL_AD_UNIT_ID

    /** A banner / native / interstitial that got "no fill" is requested again after this long (doubling), at most this many times. */
    const val RETRY_BASE_MS = 60_000L
    const val RETRY_MAX_TRIES = 5

    // ---- How often the disconnect interstitial may appear (all in one place; raise numbers to show it LESS) ----
    /** Never on the user's first N disconnects after install: let them try the app first. */
    const val INTERSTITIAL_SKIP_FIRST_DISCONNECTS = 2

    /** After that, only on every Nth disconnect (2 = every other disconnect). */
    const val INTERSTITIAL_EVERY_N_DISCONNECTS = 2

    /** At least this long between two interstitials, even across app restarts (5 minutes). */
    const val INTERSTITIAL_MIN_INTERVAL_MS = 5 * 60_000L

    /** Hard cap per calendar day (UTC). */
    const val INTERSTITIAL_MAX_PER_DAY = 4

    /** Debug / test-ads builds ignore the rules above (so you can see the ad) except for this short gap. */
    const val INTERSTITIAL_TEST_INTERVAL_MS = 10_000L
}
