package com.fastvpnn.app.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * The only ad entry point screens should use:
 *
 *   AdsManager.showBanner(activity, container) / hideBanner()
 *   AdsManager.preloadInterstitial() / isInterstitialReady() / showInterstitial(activity) { continueAction() }
 *   AdsManager.preloadRewarded() / isRewardedReady() / showRewarded(activity, onReward, onComplete)
 *   AdsManager.loadNativeAd(context, onLoaded, onFailed)  +  AdsManager.showNativeBanner(activity, container)
 *
 * Networks are tried in AdConfig.PROVIDER_ORDER (Meta, then Unity). That is a DIRECT FALLBACK implemented
 * in this app, not formal mediation: there is no bidding or eCPM comparison between the networks.
 * Everything runs on the main thread. Nothing here can throw into UI code, and every callback you pass
 * is guaranteed to run even when no ad can be shown.
 */
object AdsManager {

    private val main = Handler(Looper.getMainLooper())
    private val appStartedAt = SystemClock.elapsedRealtime()

    private val providers: List<AdProvider> by lazy {
        AdConfig.PROVIDER_ORDER.map { network ->
            when (network) {
                AdNetwork.META -> MetaAdsProvider()
                AdNetwork.UNITY -> UnityAdsProvider()
            }
        }
    }

    private var appContext: Application? = null
    private var started = false
    private var adsEnabled = false

    // frequency state (session = process lifetime)
    private var lastFullScreenAt = 0L
    private var shownThisSession = 0
    private var fullScreenActive = false

    // ============================ init ============================

    /**
     * Safe to call many times. Does nothing until the user has made an ad-privacy choice
     * (see AdsConsent) and never blocks: the SDKs initialise asynchronously.
     */
    fun init(app: Application) {
        appContext = app
        if (started) return
        when (AdsConsent.decision(app)) {
            AdsConsent.Decision.UNSET -> { AdLog.d("Ads waiting for the user's privacy choice"); return }
            AdsConsent.Decision.DISABLED -> { adsEnabled = false; AdLog.d("Ads disabled (consent not given in this region)"); return }
            AdsConsent.Decision.PERSONALIZED, AdsConsent.Decision.LIMITED -> {}
        }
        started = true
        adsEnabled = true
        val limited = AdsConsent.decision(app) == AdsConsent.Decision.LIMITED
        AppOpenAdManager.attach(app)
        providers.forEach { p ->
            try {
                p.applyPrivacy(limited)
                p.initialize(app) { ok -> onProviderInitialized(p, ok) }
            } catch (t: Throwable) {
                AdLog.w("${p.network} init error: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun onProviderInitialized(p: AdProvider, ok: Boolean) {
        if (!ok) {
            AdLog.w("${p.network} unavailable -- other networks keep working")
            retryPendingUi()
            return
        }
        val ctx = appContext ?: return
        if (adsEnabled) {
            if (p.hasInterstitial) p.loadInterstitial(ctx)
            if (p.hasRewarded) p.loadRewarded(ctx)
        }
        retryPendingUi()
    }

    /** Called by AdsConsent after the user changes the choice. */
    internal fun onConsentChanged(app: Application) {
        appContext = app
        when (val d = AdsConsent.decision(app)) {
            AdsConsent.Decision.UNSET -> {}
            AdsConsent.Decision.DISABLED -> {
                adsEnabled = false
                hideBanner()
                hideNativeBanner()
            }
            else -> {
                adsEnabled = true
                val limited = d == AdsConsent.Decision.LIMITED
                providers.forEach { it.applyPrivacy(limited) }
                if (!started) init(app) else retryPendingUi()
            }
        }
    }

    fun isEnabled(): Boolean = adsEnabled

    // ============================ banner ============================

    private class UiRequest(val activity: WeakReference<Activity>, val container: WeakReference<ViewGroup>, val token: Int)

    private var bannerRequest: UiRequest? = null
    private var bannerToken = 0
    private var bannerLoading = false
    private var bannerActive: AdProvider? = null

    private var nativeBannerRequest: UiRequest? = null
    private var nativeBannerToken = 0
    private var nativeBannerLoading = false
    private var nativeBannerActive: AdProvider? = null

    /** Loads a banner into [container]. The container stays hidden until an ad has really loaded. */
    fun showBanner(activity: Activity, container: ViewGroup) {
        hideBanner()
        container.visibility = View.GONE
        if (!adsEnabled && AdsConsent.decision(activity) != AdsConsent.Decision.UNSET) return
        bannerRequest = UiRequest(WeakReference(activity), WeakReference(container), ++bannerToken)
        tryBanner(0, bannerToken)
    }

    /** Removes the banner. Pass the container to only hide a banner that belongs to it (safe in onDestroy). */
    fun hideBanner(container: ViewGroup? = null) {
        val req = bannerRequest
        if (container != null && req != null && req.container.get() !== container) return
        bannerToken++
        bannerLoading = false
        providers.forEach { it.destroyBanner() }
        bannerActive = null
        req?.container?.get()?.let { it.removeAllViews(); it.visibility = View.GONE }
        bannerRequest = null
    }

    private fun tryBanner(startIndex: Int, token: Int) {
        val req = bannerRequest ?: return
        if (req.token != token || bannerLoading || bannerActive != null) return
        val activity = req.activity.get()
        val container = req.container.get()
        if (activity == null || container == null || activity.isFinishing || activity.isDestroyed) return
        for (i in startIndex until providers.size) {
            val p = providers[i]
            if (p.initState == InitState.INITIALIZING || p.initState == InitState.NOT_STARTED) {
                if (started) return // wait: retried from onProviderInitialized
                continue
            }
            if (p.initState != InitState.READY || !p.hasBanner) continue
            bannerLoading = true
            AdLog.d("Banner loading via ${p.network}")
            p.loadBanner(activity, container) { ok ->
                if (req.token != bannerToken) return@loadBanner // screen changed/destroyed meanwhile
                bannerLoading = false
                if (ok) {
                    bannerActive = p
                    container.visibility = View.VISIBLE
                } else {
                    tryBanner(i + 1, token) // fallback to the next network, once each: no loop
                }
            }
            return
        }
        container.visibility = View.GONE
    }

    // ============================ native banner ============================

    fun showNativeBanner(activity: Activity, container: ViewGroup) {
        hideNativeBanner()
        container.visibility = View.GONE
        if (!adsEnabled && AdsConsent.decision(activity) != AdsConsent.Decision.UNSET) return
        nativeBannerRequest = UiRequest(WeakReference(activity), WeakReference(container), ++nativeBannerToken)
        tryNativeBanner(0, nativeBannerToken)
    }

    fun hideNativeBanner(container: ViewGroup? = null) {
        val req = nativeBannerRequest
        if (container != null && req != null && req.container.get() !== container) return
        nativeBannerToken++
        nativeBannerLoading = false
        providers.forEach { it.destroyNativeBanner() }
        nativeBannerActive = null
        req?.container?.get()?.let { it.removeAllViews(); it.visibility = View.GONE }
        nativeBannerRequest = null
    }

    private fun tryNativeBanner(startIndex: Int, token: Int) {
        val req = nativeBannerRequest ?: return
        if (req.token != token || nativeBannerLoading || nativeBannerActive != null) return
        val activity = req.activity.get()
        val container = req.container.get()
        if (activity == null || container == null || activity.isFinishing || activity.isDestroyed) return
        for (i in startIndex until providers.size) {
            val p = providers[i]
            if (!p.hasNativeBanner) continue
            if (p.initState == InitState.INITIALIZING || p.initState == InitState.NOT_STARTED) {
                if (started) return
                continue
            }
            if (p.initState != InitState.READY) continue
            nativeBannerLoading = true
            p.loadNativeBanner(activity, container) { ok ->
                if (req.token != nativeBannerToken) return@loadNativeBanner
                nativeBannerLoading = false
                if (ok) {
                    nativeBannerActive = p
                    container.visibility = View.VISIBLE
                } else {
                    tryNativeBanner(i + 1, token)
                }
            }
            return
        }
        container.visibility = View.GONE
    }

    private fun retryPendingUi() {
        bannerRequest?.let { if (bannerActive == null && !bannerLoading) tryBanner(0, it.token) }
        nativeBannerRequest?.let { if (nativeBannerActive == null && !nativeBannerLoading) tryNativeBanner(0, it.token) }
    }

    // ============================ interstitial ============================

    fun preloadInterstitial() {
        val ctx = appContext ?: return
        if (!adsEnabled) return
        providers.forEach { if (it.initState == InitState.READY && it.hasInterstitial) it.loadInterstitial(ctx) }
    }

    fun isInterstitialReady(): Boolean = adsEnabled && providers.any { it.initState == InitState.READY && it.isInterstitialReady() }

    /** Frequency + safety rules shared by interstitial and app-open ads. */
    private fun canShowFullScreen(activity: Activity, connectAd: Boolean = false): Boolean {
        if (!adsEnabled || fullScreenActive) return false
        if (activity.isFinishing || activity.isDestroyed) return false
        val now = SystemClock.elapsedRealtime()
        // The Connect-tap ad is user-initiated, so it skips the "just launched" delay and uses its own gap.
        if (!connectAd && now - appStartedAt < AdConfig.MIN_TIME_AFTER_LAUNCH_MS) return false
        if (shownThisSession >= AdConfig.MAX_INTERSTITIALS_PER_SESSION) return false
        val minGap = if (connectAd) AdConfig.CONNECT_AD_MIN_INTERVAL_MS else AdConfig.MIN_INTERSTITIAL_INTERVAL_MS
        if (lastFullScreenAt != 0L && now - lastFullScreenAt < minGap) return false
        val focus = activity.currentFocus
        if (focus is EditText && focus.hasFocus()) return false // user is typing
        return true
    }

    private fun recordFullScreenShown() {
        lastFullScreenAt = SystemClock.elapsedRealtime()
        shownThisSession++
    }

    /**
     * Shows an interstitial if one is ready and the frequency rules allow it, then runs [onComplete].
     * If not, [onComplete] runs immediately -- the caller's action is never delayed by a missing ad.
     * [onComplete] runs exactly once.
     */
    fun showInterstitial(activity: Activity, onComplete: () -> Unit) = showInterstitialInternal(activity, "interstitial", false, onComplete)

    /** Same, for the CONNECT tap: allowed right after launch and with AdConfig.CONNECT_AD_MIN_INTERVAL_MS. */
    fun showConnectInterstitial(activity: Activity, onComplete: () -> Unit) = showInterstitialInternal(activity, "connect-interstitial", true, onComplete)

    internal fun showAppOpen(activity: Activity) = showInterstitialInternal(activity, "app-open", false, {})

    private fun showInterstitialInternal(activity: Activity, label: String, connectAd: Boolean, onComplete: () -> Unit) {
        var finished = false
        val done = { if (!finished) { finished = true; onComplete() } }
        try {
            if (!canShowFullScreen(activity, connectAd)) {
                if (!isInterstitialReady()) preloadInterstitial()
                done(); return
            }
            if (!isInterstitialReady()) {
                AdLog.d("$label not ready -- continuing")
                preloadInterstitial()
                done(); return
            }
            fullScreenActive = true
            showInterstitialFrom(0, activity, label, done)
        } catch (t: Throwable) {
            fullScreenActive = false
            AdLog.w("showInterstitial error: ${t.javaClass.simpleName}")
            done()
        }
    }

    private fun showInterstitialFrom(startIndex: Int, activity: Activity, label: String, done: () -> Unit) {
        for (i in startIndex until providers.size) {
            val p = providers[i]
            if (p.initState != InitState.READY || !p.isInterstitialReady()) continue
            var displayed = false
            var settled = false
            lateinit var watchdog: Runnable
            watchdog = Runnable {
                if (!displayed && !settled) {
                    settled = true
                    AdLog.w("$label from ${p.network} did not start in time -- continuing")
                    fullScreenActive = false
                    done()
                }
            }
            main.postDelayed(watchdog, AdConfig.SHOW_START_TIMEOUT_MS)
            AdLog.d("$label showing via ${p.network}")
            val callbacks = FullScreenCallbacks(
                onDisplayed = {
                    displayed = true
                    main.removeCallbacks(watchdog)
                    recordFullScreenShown()
                },
                onClosed = {
                    main.removeCallbacks(watchdog)
                    settled = true
                    fullScreenActive = false
                    preloadInterstitial() // immediately prepare the next one
                    done()
                },
                onFailed = {
                    main.removeCallbacks(watchdog)
                    if (displayed || settled) {
                        settled = true
                        fullScreenActive = false
                        done()
                    } else {
                        settled = true
                        AdLog.d("$label failed on ${p.network} -- trying next network")
                        showInterstitialFrom(i + 1, activity, label, done)
                    }
                }
            )
            p.showInterstitial(activity, callbacks)
            return
        }
        // nobody could show it
        fullScreenActive = false
        preloadInterstitial()
        done()
    }

    // ============================ rewarded ============================

    fun preloadRewarded() {
        val ctx = appContext ?: return
        if (!adsEnabled) return
        providers.forEach { if (it.initState == InitState.READY && it.hasRewarded) it.loadRewarded(ctx) }
    }

    fun isRewardedReady(): Boolean = adsEnabled && providers.any { it.initState == InitState.READY && it.isRewardedReady() }

    /**
     * [onReward] runs only after the network confirms the user earned the reward -- never just because the ad opened.
     * [onComplete] always runs once when the flow ends (closed, failed, or no ad available).
     */
    fun showRewarded(activity: Activity, onReward: () -> Unit, onComplete: () -> Unit = {}) {
        var finished = false
        val done = { if (!finished) { finished = true; onComplete() } }
        var rewarded = false
        val grant = { if (!rewarded) { rewarded = true; onReward() } }
        try {
            val index = providers.indexOfFirst { it.initState == InitState.READY && it.isRewardedReady() }
            if (!adsEnabled || fullScreenActive || index < 0 || activity.isFinishing || activity.isDestroyed) {
                preloadRewarded()
                Toast.makeText(activity, "Reward video isn't available right now. Please try again in a moment.", Toast.LENGTH_SHORT).show()
                done(); return
            }
            val p = providers[index]
            fullScreenActive = true
            var displayed = false
            lateinit var watchdog: Runnable
            watchdog = Runnable {
                if (!displayed) {
                    fullScreenActive = false
                    done()
                }
            }
            main.postDelayed(watchdog, AdConfig.SHOW_START_TIMEOUT_MS)
            p.showRewarded(activity, FullScreenCallbacks(
                onDisplayed = { displayed = true; main.removeCallbacks(watchdog) },
                onRewarded = { grant() },
                onClosed = { main.removeCallbacks(watchdog); fullScreenActive = false; preloadRewarded(); done() },
                onFailed = { main.removeCallbacks(watchdog); fullScreenActive = false; preloadRewarded(); done() }
            ))
        } catch (t: Throwable) {
            fullScreenActive = false
            AdLog.w("showRewarded error: ${t.javaClass.simpleName}")
            done()
        }
    }

    // ============================ native ============================

    /**
     * Loads one native ad from the first network that offers the format (currently Meta only --
     * Unity Ads has no native format). The caller must call [NativeAdHandle.destroy] when finished.
     */
    fun loadNativeAd(context: Context, onLoaded: (NativeAdHandle) -> Unit, onFailed: () -> Unit = {}) {
        val p = providers.firstOrNull { it.hasNative && it.initState == InitState.READY }
        if (!adsEnabled || p == null) { onFailed(); return }
        try {
            p.loadNative(context, onLoaded, onFailed)
        } catch (t: Throwable) {
            onFailed()
        }
    }
}
