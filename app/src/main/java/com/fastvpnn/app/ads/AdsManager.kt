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
import com.unity3d.ads.BannerAd
import com.unity3d.ads.BannerConfiguration
import com.unity3d.ads.BannerShowListener
import com.unity3d.ads.BannerSize
import com.unity3d.ads.InitializationConfiguration
import com.unity3d.ads.InitializationListener
import com.unity3d.ads.LoadListener
import com.unity3d.ads.LogLevel
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsError
import com.unity3d.ads.UnityAdsExperimental
import java.lang.ref.WeakReference

/**
 * The only ad entry point: ONE Unity banner.
 *
 *   AdsManager.init(app)                    -- safe to call repeatedly; starts Unity once the user has allowed ads
 *   AdsManager.showBanner(activity, view)   -- view stays hidden until an ad has really loaded
 *   AdsManager.hideBanner(view)             -- call from onDestroy
 *
 * Uses the SDK 4.20+ API (InitializationConfiguration + BannerAd.load), the same calls the previously working
 * project used. The legacy BannerView / UnityAds.initialize(context, ...) path was removed from this file.
 * Everything runs on the main thread and nothing here can throw into UI code.
 */
object AdsManager {

    private const val TAG = "FastVPN-Ads"

    private enum class State { NOT_STARTED, INITIALIZING, READY, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var state = State.NOT_STARTED

    private class Request(val activity: WeakReference<Activity>, val container: WeakReference<ViewGroup>, val token: Int)

    private var request: Request? = null
    private var token = 0
    private var banner: BannerAd? = null
    private var loading = false
    private var active = false
    private var retries = 0
    private var retryQueued = false

    // ============================ init ============================

    /** Does nothing until the user has allowed ads (see AdsConsent). Never blocks: Unity initialises asynchronously. */
    @OptIn(UnityAdsExperimental::class)
    fun init(app: Application) {
        if (state != State.NOT_STARTED || !AdsConsent.isAccepted(app)) return
        state = State.INITIALIZING
        try {
            // The user allowed ads in the consent dialog.
            try {
                UnityAds.userConsent = true
                UnityAds.nonBehavioral = false
            } catch (t: Throwable) {
                Log.w(TAG, "Unity privacy flags failed: ${t.javaClass.simpleName}")
            }
            val config = InitializationConfiguration.Builder(AdConfig.GAME_ID)
                .withTestMode(AdConfig.isTestMode)
                .withLogLevel(if (AdConfig.isTestMode) LogLevel.DEBUG else LogLevel.ERROR)
                .build()
            UnityAds.initialize(config, InitializationListener { error ->
                if (error == null) {
                    state = State.READY
                    Log.i(TAG, "Unity initialized (test=${AdConfig.isTestMode})")
                    request?.let { if (!active && !loading && !retryQueued) tryLoad(it.token) }
                } else {
                    state = State.FAILED
                    Log.w(TAG, "Unity init failed: error=$error")
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
        try { banner?.view?.let { v -> (v.parent as? ViewGroup)?.removeView(v) } } catch (_: Throwable) {}
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
            val showListener = object : BannerShowListener {
                override fun onImpression(banner: BannerAd) {}
                override fun onClicked(banner: BannerAd) {}
                override fun onFailedToShow(banner: BannerAd, error: UnityAdsError) {
                    Log.w(TAG, "banner failed to show: code=${error.code} error=$error")
                }
            }
            val config = BannerConfiguration.Builder(AdConfig.BANNER_PLACEMENT_ID, BannerSize(320, 50), showListener).build()
            BannerAd.load(config, object : LoadListener<BannerAd> {
                override fun onAdLoaded(ad: BannerAd?, error: UnityAdsError?) {
                    if (req.token != token) { // screen changed/destroyed while loading
                        return
                    }
                    loading = false
                    val view = ad?.view
                    if (ad == null || view == null || error != null || activity.isFinishing || activity.isDestroyed) {
                        Log.i(TAG, "banner failed: code=${error?.code} error=$error")
                        container.removeAllViews()
                        container.visibility = View.GONE
                        scheduleRetry(forToken)
                        return
                    }
                    banner = ad
                    active = true
                    container.removeAllViews()
                    container.addView(
                        view,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
                    )
                    container.visibility = View.VISIBLE
                    Log.i(TAG, "banner loaded")
                }
            })
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
