package com.fastvpnn.app.ads

import com.fastvpnn.app.BuildConfig

/**
 * THE one place for ad IDs, test/release switching and frequency rules.
 *
 *  - Debug builds always run in test mode (Meta: "IMG_16_9_APP_INSTALL#" test prefix, Unity: testMode).
 *  - Release builds run real ads, unless USE_TEST_ADS is set to true for the release build type in app/build.gradle.
 *  - Leave an ID empty ("") and that format is simply switched off for that network (no crash, no request).
 *
 * Meta has no separate "test placement IDs": test ads are requested by prefixing YOUR placement ID, which is
 * done automatically in debug builds. Unity has no test placement IDs either: test mode is a flag on init.
 */
object AdConfig {

    /** One network's IDs. */
    data class MetaIds(
        val appId: String,            // for reference/logging only -- the Meta SDK takes no app ID in code
        val bannerId: String,
        val interstitialId: String,
        val nativeId: String,
        val nativeBannerId: String,
        val rewardedId: String,
    )

    data class UnityIds(
        val gameId: String,
        val bannerPlacementId: String,
        val interstitialPlacementId: String,
        val rewardedPlacementId: String,
    )

    // ====================== EDIT YOUR IDs HERE ======================

    private val META_DEBUG = MetaIds(
        appId = "1437978778491299",
        bannerId = "1437978778491299_1437983005157543",
        interstitialId = "1437978778491299_1437983435157500",
        nativeId = "1437978778491299_1437983681824142",
        nativeBannerId = "1437978778491299_1437984198490757",
        rewardedId = "",              // no Meta rewarded placement created yet
    )
    private val META_RELEASE = META_DEBUG.copy() // change any field here if release should use different placements

    private val UNITY_DEBUG = UnityIds(
        gameId = "800391481",
        bannerPlacementId = "BP_Banner_Android",
        interstitialPlacementId = "BP_Interstitial_Android",
        rewardedPlacementId = "",     // no Unity rewarded placement created yet
    )
    private val UNITY_RELEASE = UNITY_DEBUG.copy()

    // ================================================================

    /** Which network is tried first / second. Direct fallback, not formal mediation -- see UPGRADE_NOTES.md. */
    internal val PROVIDER_ORDER = listOf(AdNetwork.META, AdNetwork.UNITY)

    /** true => test ads only. */
    val isTestMode: Boolean get() = BuildConfig.DEBUG || BuildConfig.USE_TEST_ADS

    internal val meta: MetaIds get() = if (isTestMode) META_DEBUG else META_RELEASE
    internal val unity: UnityIds get() = if (isTestMode) UNITY_DEBUG else UNITY_RELEASE

    // ---------------- frequency control (all easy to change) ----------------

    /** Minimum gap between two full-screen ads (interstitial and app-open share it). */
    const val MIN_INTERSTITIAL_INTERVAL_MS = 90_000L

    /**
     * The interstitial shown when the user taps CONNECT (only when the VPN is not already connected).
     * It is allowed right after launch and uses this shorter gap. 0 = show it on every Connect tap if an ad is loaded.
     */
    const val CONNECT_AD_MIN_INTERVAL_MS = 0L

    /** Hard cap per app session (= process lifetime). */
    const val MAX_INTERSTITIALS_PER_SESSION = 12

    /** No full-screen ad in the first seconds after the app process starts. */
    const val MIN_TIME_AFTER_LAUNCH_MS = 30_000L

    /** App-open style ad only after the app was in the background at least this long. */
    const val APP_OPEN_MIN_BACKGROUND_MS = 30_000L

    /** If a full-screen ad has not started displaying this fast, give up and continue the user's action. */
    const val SHOW_START_TIMEOUT_MS = 5_000L

    /** After a failed load, wait this long (doubling, capped) before another load is attempted. No timers, no loops. */
    const val LOAD_RETRY_BASE_MS = 20_000L
    const val LOAD_RETRY_MAX_MS = 5 * 60_000L

    // ---------------- helpers ----------------

    private fun usable(id: String) = id.isNotBlank() && !id.startsWith("YOUR_")

    /** Meta placement ID to request, test-prefixed in test mode; null when not configured. */
    internal fun metaPlacement(id: String): String? =
        if (!usable(id)) null else if (isTestMode) "IMG_16_9_APP_INSTALL#$id" else id

    internal fun unityPlacement(id: String): String? = if (usable(id)) id else null

    /** Never print full IDs in logs. */
    internal fun mask(id: String): String = if (id.length <= 6) "***" else id.take(3) + "***" + id.takeLast(3)
}
