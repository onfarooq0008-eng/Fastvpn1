package com.fastvpnn.app.ads

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.metadata.MetaData
import com.unity3d.services.banners.BannerErrorInfo
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize
import java.lang.ref.WeakReference

/**
 * The only ad entry point: ONE Unity banner.
 *
 *   AdsManager.init(app)                    -- safe to call repeatedly; starts Unity once the user has allowed ads
 *   AdsManager.showBanner(activity, view)   -- view stays hidden until an ad has really loaded
 *   AdsManager.hideBanner(view)             -- call from onDestroy
 *
 * Uses Unity's BannerView API (still shipped in SDK 4.21, marked deprecated since 4.19). Everything runs on
 * the main thread and nothing here can throw into UI code.
 */
object AdsManager {

    private const val TAG = "FastVPN-Ads"

    private enum class State { NOT_STARTED, INITIALIZING, READY, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var state = State.NOT_STARTED

    private class Request(val activity: WeakReference<Activity>, val container: WeakReference<ViewGroup>, val token: Int)

    private var request: Request? = null
    private var token = 0
    private var banner: BannerView? = null
    private var loading = false
    private var active = false
    private var retries = 0
    private var retryQueued = false

    // ============================ init ============================

    /** Does nothing until the user has allowed ads (see AdsConsent). Never blocks: Unity initialises asynchronously. */
    fun init(app: Application) {
        if (state != State.NOT_STARTED || !AdsConsent.isAccepted(app)) return
        state = State.INITIALIZING
        try {
            // The user allowed ads in the consent dialog.
            MetaData(app).apply { set("gdpr.consent", true); commit() }
            MetaData(app).apply { set("privacy.consent", true); commit() }
            UnityAds.initialize(app, AdConfig.GAME_ID, AdConfig.isTestMode, object : IUnityAdsInitializationListener {
                override fun onInitializationComplete() {
                    state = State.READY
                    Log.i(TAG, "Unity initialized (test=${AdConfig.isTestMode})")
                    request?.let { if (!active && !loading && !retryQueued) tryLoad(it.token) }
                }

                override fun onInitializationFailed(error: UnityAds.UnityAdsInitializationError?, message: String?) {
                    state = State.FAILED
                    Log.w(TAG, "Unity init failed: $error $message")
                }
            })
        } catch (t: Throwable) {
            state = State.FAILED
            Log.w(TAG, "Unity init crashed: ${t.javaClass.simpleName}")
        }
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
        if (state != State.READY) return // loaded from onInitializationComplete once Unity is up
        loading = true
        try {
            val view = BannerView(activity, AdConfig.BANNER_PLACEMENT_ID, UnityBannerSize(320, 50))
            banner = view
            var reported = false // Unity refreshes banners itself and can call onBannerLoaded repeatedly
            view.listener = object : BannerView.IListener {
                override fun onBannerLoaded(bannerAdView: BannerView?) {
                    if (reported || req.token != token) return
                    reported = true
                    loading = false
                    active = true
                    container.visibility = View.VISIBLE
                    Log.i(TAG, "banner loaded")
                }

                override fun onBannerFailedToLoad(bannerAdView: BannerView?, errorInfo: BannerErrorInfo?) {
                    Log.i(TAG, "banner failed: code=${errorInfo?.errorCode} msg=${errorInfo?.errorMessage}")
                    if (reported || req.token != token) return // a later refresh failing keeps the last ad on screen
                    reported = true
                    loading = false
                    try { view.destroy() } catch (_: Throwable) {}
                    if (banner === view) banner = null
                    container.removeAllViews()
                    container.visibility = View.GONE
                    scheduleRetry(forToken)
                }

                // Required since Unity Ads 4.21 (the interface gained this callback).
                override fun onBannerShown(bannerAdView: BannerView?) {}
                override fun onBannerClick(bannerAdView: BannerView?) {}
                override fun onBannerLeftApplication(bannerAdView: BannerView?) {}
            }
            container.removeAllViews()
            container.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
            )
            view.load()
        } catch (t: Throwable) {
            loading = false
            Log.w(TAG, "banner crashed: ${t.javaClass.simpleName}")
            container.removeAllViews()
            container.visibility = View.GONE
        }
    }

    /** "No fill" is common for new apps. Ask again later, a few times, never in a tight loop. */
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
