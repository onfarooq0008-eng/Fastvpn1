package com.fastvpnn.app.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import com.fastvpnn.app.BuildConfig
import com.fastvpnn.app.databinding.ItemNativeAdBinding

internal enum class AdNetwork { META, UNITY }

internal enum class InitState { NOT_STARTED, INITIALIZING, READY, FAILED }

/** Callbacks for full-screen ads (interstitial / rewarded). Each is invoked at most once per show. */
internal class FullScreenCallbacks(
    val onDisplayed: () -> Unit,
    val onClosed: () -> Unit,
    val onFailed: () -> Unit,
    val onRewarded: (() -> Unit)? = null,
)

/** A loaded native ad, independent of the network that served it. */
interface NativeAdHandle {
    val isValid: Boolean
    /** Fills the reusable item_native_ad layout (icon, headline, body, media, CTA, AdChoices, "Sponsored"). */
    fun render(binding: ItemNativeAdBinding)
    fun destroy()
}

/** What every ad network implementation has to offer. UI code never touches this -- only AdsManager does. */
internal interface AdProvider {
    val network: AdNetwork
    val initState: InitState

    val hasBanner: Boolean
    val hasInterstitial: Boolean
    val hasRewarded: Boolean
    val hasNative: Boolean
    val hasNativeBanner: Boolean

    fun applyPrivacy(limited: Boolean)
    fun initialize(app: Application, onDone: (Boolean) -> Unit)

    fun loadBanner(activity: Activity, container: ViewGroup, onResult: (Boolean) -> Unit)
    fun destroyBanner()

    fun loadInterstitial(context: Context)
    fun isInterstitialReady(): Boolean
    fun showInterstitial(activity: Activity, callbacks: FullScreenCallbacks)

    fun loadRewarded(context: Context)
    fun isRewardedReady(): Boolean
    fun showRewarded(activity: Activity, callbacks: FullScreenCallbacks)

    fun loadNative(context: Context, onLoaded: (NativeAdHandle) -> Unit, onFailed: () -> Unit)

    fun loadNativeBanner(activity: Activity, container: ViewGroup, onResult: (Boolean) -> Unit)
    fun destroyNativeBanner()
}

internal object AdLog {
    private const val TAG = "FastVPN-Ads"
    /** Verbose logs only in debug builds. IDs are never logged in full and no user data is logged. */
    fun d(msg: String) { if (BuildConfig.DEBUG) Log.d(TAG, "[ADS] $msg") }
    fun w(msg: String) { Log.w(TAG, "[ADS] $msg") }
}

/** Per-format load throttle: one load at a time, exponential cool-down after failures, never a retry loop. */
internal class LoadGate {
    var loading = false
        private set
    private var failures = 0
    private var nextAllowedAt = 0L

    fun tryStart(): Boolean {
        if (loading) return false
        if (SystemClock.elapsedRealtime() < nextAllowedAt) return false
        loading = true
        return true
    }

    fun success() {
        loading = false
        failures = 0
        nextAllowedAt = 0L
    }

    fun failure() {
        loading = false
        failures++
        val delay = (AdConfig.LOAD_RETRY_BASE_MS shl (failures - 1).coerceAtMost(10)).coerceAtMost(AdConfig.LOAD_RETRY_MAX_MS)
        nextAllowedAt = SystemClock.elapsedRealtime() + delay
    }
}
