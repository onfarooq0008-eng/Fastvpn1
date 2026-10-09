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
    private val history = java.util.ArrayDeque<String>()

    private fun record(msg: String) {
        synchronized(history) {
            if (history.size >= 60) history.removeFirst()
            history.addLast(msg)
        }
    }

    /** Logged in release too (adb logcat -s FastVPN-Ads) and kept for Settings > About (long-press). No user data is logged. */
    fun d(msg: String) { Log.i(TAG, "[ADS] $msg"); record(msg) }
    fun w(msg: String) { Log.w(TAG, "[ADS] $msg"); record(msg) }

    /** The last ad events, oldest first, for the on-device ad status screen. */
    fun history(): String = synchronized(history) { history.joinToString("\n") }
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
