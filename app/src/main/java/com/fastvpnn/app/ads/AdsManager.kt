package com.fastvpnn.app.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.banner.BannerAdView
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.interstitial.InterstitialAd
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader
import java.lang.ref.WeakReference

/**
 * The ad entry point: Yandex banner + disconnect interstitial (native ads live in [NativeAdSlot]). Google AdMob, Start.io
 * and the other networks are mediation partners behind Yandex, configured in the Yandex dashboard.
 *
 *   AdsManager.init(app)                    -- safe to call repeatedly; starts the SDK once the user has allowed ads
 *   AdsManager.showBanner(activity, view)   -- view stays hidden until an ad has really loaded
 *   AdsManager.hideBanner(view)             -- call from onDestroy
 *   AdsManager.showInterstitial(activity)   -- call right after a user-initiated disconnect (rate-limited, no-op if no ad is ready)
 *   AdsManager.whenReady(runnable)          -- runs once the SDK is up (used by native ad slots)
 *
 * The SDK's own automatic start is switched off in the manifest, so nothing from Yandex, AdMob or Start.io
 * runs before the user has said yes. Everything here runs on the main thread and nothing can throw into UI code.
 */
object AdsManager {

    private const val TAG = "FastVPN-Ads"

    private enum class State { NOT_STARTED, INITIALIZING, READY, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var state = State.NOT_STARTED

    private class Request(val activity: WeakReference<Activity>, val container: WeakReference<ViewGroup>, val token: Int)

    private var request: Request? = null
    private var token = 0
    private var banner: BannerAdView? = null
    private var loading = false
    private var active = false
    private var retries = 0
    private var retryQueued = false

    // ---- interstitial ----
    private var appContext: Application? = null
    private var interstitialLoader: InterstitialAdLoader? = null // strong reference, as Yandex requires
    private var interstitial: InterstitialAd? = null
    private var shownInterstitial: InterstitialAd? = null
    private var interstitialLoading = false
    private var interstitialRetries = 0
    private var lastInterstitialShownAt = 0L

    // ---- "SDK is ready" callbacks ----
    private val readyListeners = mutableListOf<Runnable>()

    /** Runs [r] on the main thread once the SDK is initialised (now, if it already is). */
    fun whenReady(r: Runnable) {
        if (state == State.READY) {
            r.run()
        } else if (!readyListeners.contains(r)) {
            readyListeners.add(r)
        }
    }

    fun removeReadyListener(r: Runnable) {
        readyListeners.remove(r)
    }

    /** In debug / test-ads builds, tell the developer WHY an ad is missing (no logcat needed). Never shown to real users. */
    private fun debugToast(context: Context, message: String) {
        if (AdConfig.isTestMode) Toast.makeText(context, "Ads: $message", Toast.LENGTH_LONG).show()
    }

    // ============================ init ============================

    /** Does nothing until the user has allowed ads (see AdsConsent). Never blocks: the SDK starts asynchronously. */
    fun init(app: Application) {
        if (state != State.NOT_STARTED || !AdsConsent.isAccepted(app)) return
        state = State.INITIALIZING
        appContext = app
        try {
            // The user allowed ads in the consent dialog. Must be set BEFORE initialize(); Yandex hands it on to
            // the mediation partners (AdMob, Start.io -- Start.io then skips its own consent pop-up).
            YandexAds.setUserConsent(true)
            YandexAds.initialize(app) {
                state = State.READY
                Log.i(TAG, "Yandex Ads initialized (test=${AdConfig.isTestMode})")
                request?.let { if (!active && !loading && !retryQueued) tryLoad(it.token) }
                loadInterstitial()
                readyListeners.toList().also { readyListeners.clear() }.forEach { it.run() }
            }
        } catch (t: Throwable) {
            state = State.FAILED
            Log.w(TAG, "Yandex Ads init crashed: ${t.javaClass.simpleName}")
            debugToast(app, "init crashed: ${t.javaClass.simpleName}")
        }
    }

    // ============================ interstitial ============================

    private fun loadInterstitial() {
        val app = appContext ?: return
        if (state != State.READY || interstitialLoading || interstitial != null) return
        interstitialLoading = true
        try {
            val loader = interstitialLoader ?: InterstitialAdLoader(app).also { interstitialLoader = it }
            loader.loadAd(
                AdRequest.Builder(AdConfig.interstitialAdUnitId).build(),
                object : InterstitialAdLoadListener {
                    override fun onAdLoaded(interstitialAd: InterstitialAd) {
                        interstitialLoading = false
                        interstitialRetries = 0
                        interstitial = interstitialAd
                        Log.i(TAG, "interstitial loaded")
                    }

                    override fun onAdFailedToLoad(adRequestError: AdRequestError) {
                        interstitialLoading = false
                        Log.i(TAG, "interstitial failed: code=${adRequestError.code} msg=${adRequestError.description}")
                        // Never reload straight from this callback (Yandex discourages it): retry later, a few times.
                        if (interstitialRetries < AdConfig.RETRY_MAX_TRIES) {
                            val delay = AdConfig.RETRY_BASE_MS shl interstitialRetries
                            interstitialRetries++
                            main.postDelayed({ loadInterstitial() }, delay)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            interstitialLoading = false
            Log.w(TAG, "interstitial crashed: ${t.javaClass.simpleName}")
        }
    }

    /** Shows the preloaded interstitial, if there is one and the last one was long enough ago. Never blocks, never throws. */
    fun showInterstitial(activity: Activity) {
        if (state != State.READY || activity.isFinishing || activity.isDestroyed) return
        val ad = interstitial
        if (ad == null) {
            loadInterstitial() // not ready yet: try to have one for next time
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastInterstitialShownAt != 0L && now - lastInterstitialShownAt < AdConfig.INTERSTITIAL_MIN_INTERVAL_MS) return
        try {
            interstitial = null
            shownInterstitial = ad
            ad.setAdEventListener(object : InterstitialAdEventListener {
                override fun onAdShown() {}

                override fun onAdFailedToShow(adError: AdError) {
                    Log.i(TAG, "interstitial failed to show: ${adError.description}")
                    finishInterstitial(ad)
                }

                override fun onAdDismissed() = finishInterstitial(ad)
                override fun onAdClicked() {}
                override fun onAdImpression(impressionData: ImpressionData?) {}
            })
            lastInterstitialShownAt = now
            ad.show(activity)
        } catch (t: Throwable) {
            Log.w(TAG, "interstitial show crashed: ${t.javaClass.simpleName}")
            finishInterstitial(ad)
        }
    }

    /** After an interstitial is dismissed (or failed to show): release it, as Yandex asks, and preload the next one. */
    private fun finishInterstitial(ad: InterstitialAd) {
        try { ad.setAdEventListener(null) } catch (_: Throwable) {}
        if (shownInterstitial === ad) shownInterstitial = null
        loadInterstitial()
    }

    // ============================ banner ============================

    /** Loads a banner into [container]. The container stays hidden until an ad has really loaded. */
    fun showBanner(activity: Activity, container: ViewGroup) {
        hideBanner()
        container.visibility = View.GONE
        request = Request(WeakReference(activity), WeakReference(container), ++token)
        retries = 0
        tryLoad(token)
    }

    /** Removes the banner. Pass the container to only hide a banner that belongs to it (safe in onDestroy). */
    fun hideBanner(container: ViewGroup? = null) {
        val req = request
        if (container != null && req != null && req.container.get() !== container) return
        token++
        loading = false
        active = false
        retryQueued = false
        try { banner?.destroy() } catch (_: Throwable) {}
        banner = null
        req?.container?.get()?.let { it.removeAllViews(); it.visibility = View.GONE }
        request = null
    }

    private fun tryLoad(forToken: Int) {
        val req = request ?: return
        if (req.token != forToken || loading || active) return
        val activity = req.activity.get()
        val container = req.container.get()
        if (activity == null || container == null || activity.isFinishing || activity.isDestroyed) return
        if (state != State.READY) return // loaded from the init callback once the SDK is up
        loading = true
        // The banner is a web view: it must be laid out to load, so the container is INVISIBLE (kept in
        // layout) while loading -- not GONE. It becomes VISIBLE on success and GONE on failure.
        container.visibility = View.INVISIBLE
        try {
            val view = BannerAdView(activity)
            banner = view
            // Adaptive sticky banner as wide as the screen.
            val widthDp = (activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density).toInt()
            view.setAdSize(BannerAdSize.sticky(activity, widthDp))
            var reported = false // the SDK can refresh banners itself and call onAdLoaded repeatedly
            view.setBannerAdEventListener(object : BannerAdEventListener {
                override fun onAdLoaded() {
                    if (req.token != token) {
                        // The screen moved on while this loaded: release it instead of leaking it.
                        try { view.destroy() } catch (_: Throwable) {}
                        return
                    }
                    if (reported) return
                    reported = true
                    loading = false
                    active = true
                    container.visibility = View.VISIBLE
                    Log.i(TAG, "banner loaded")
                }

                override fun onAdFailedToLoad(adRequestError: AdRequestError) {
                    Log.i(TAG, "banner failed: code=${adRequestError.code} msg=${adRequestError.description}")
                    debugToast(activity, "banner failed: ${adRequestError.code} ${adRequestError.description}")
                    if (reported || req.token != token) return // a later refresh failing keeps the last ad on screen
                    reported = true
                    loading = false
                    try { view.destroy() } catch (_: Throwable) {}
                    if (banner === view) banner = null
                    container.removeAllViews()
                    container.visibility = View.GONE
                    scheduleRetry(forToken)
                }

                override fun onAdClicked() {}
                override fun onImpression(impressionData: ImpressionData?) {}
            })
            container.removeAllViews()
            container.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
            )
            view.loadAd(AdRequest.Builder(AdConfig.bannerAdUnitId).build())
        } catch (t: Throwable) {
            loading = false
            Log.w(TAG, "banner crashed: ${t.javaClass.simpleName}")
            debugToast(activity, "banner crashed: ${t.javaClass.simpleName}")
            container.removeAllViews()
            container.visibility = View.GONE
        }
    }

    /** "No fill" is common for new apps. Ask again later (never straight from the failure callback, which Yandex
     *  discourages), a few times, with a growing delay -- never in a tight loop. */
    private fun scheduleRetry(forToken: Int) {
        if (retries >= AdConfig.RETRY_MAX_TRIES) return
        val delay = AdConfig.RETRY_BASE_MS shl retries
        retries++
        retryQueued = true
        main.postDelayed({
            retryQueued = false
            val req = request
            if (req != null && req.token == forToken && !active && !loading) tryLoad(forToken)
        }, delay)
    }
}
