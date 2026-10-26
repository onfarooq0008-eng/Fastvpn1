package com.fastvpnn.app.ads

import com.fastvpnn.app.BuildConfig

/**
 * THE one place for the ad settings.
 *
 * Ads in this app (all served by the Yandex Mobile Ads SDK; Google AdMob, Start.io and any other network are
 * Yandex Mediation partners you switch on per ad unit in the Yandex dashboard -- no code change needed):
 *  - BANNER        : one adaptive banner above the bottom navigation bar.
 *  - NATIVE        : one card on the Home screen and one inside the Locations list.
 *  - INTERSTITIAL  : full-screen ad shown right after the user taps Disconnect.
 *
 * Test ads: debug builds always use Yandex's public demo units. Release builds use the real units below,
 * unless USE_TEST_ADS is set to true for the release build type in app/build.gradle.
 *
 * The AdMob App ID (needed by the Google adapter) is set in app/build.gradle as the `admobAppId` manifest placeholder.
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

    /** The disconnect interstitial is shown at most once per this interval, so quick connect/disconnect taps are not punished with ads. */
    const val INTERSTITIAL_MIN_INTERVAL_MS = 60_000L
}
