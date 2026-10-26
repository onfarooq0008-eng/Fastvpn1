package com.fastvpnn.app.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.fastvpnn.app.R
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.nativeads.MediaView
import com.yandex.mobile.ads.nativeads.NativeAd
import com.yandex.mobile.ads.nativeads.NativeAdLoadListener
import com.yandex.mobile.ads.nativeads.NativeAdLoader
import com.yandex.mobile.ads.nativeads.NativeAdView
import com.yandex.mobile.ads.nativeads.NativeAdViewBinder

/**
 * ONE native ad: loads it, builds its view from R.layout.view_native_ad, binds it, and hands the finished view out
 * through [attachTo]. [onChanged] runs (main thread) when the ad becomes ready, so the screen can show it.
 *
 * A Yandex NativeAd can only be bound to one view, so each place that shows a native ad (Home, Locations list)
 * owns its own slot -- two slots = two separate requests of the same ad unit.
 *
 * Nothing loads until the SDK is ready (which only happens after the user allowed ads). Failures never reach the UI:
 * the slot just stays empty and retries a few times with a growing delay (never straight from the failure callback).
 */
class NativeAdSlot(private val activity: Activity, private val onChanged: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var loader: NativeAdLoader? = null // strong reference for the screen's lifetime, as Yandex requires
    private var nativeAd: NativeAd? = null
    private var loading = false
    private var retries = 0
    private var destroyed = false

    /** The bound, ready-to-show view, or null while there is no ad. */
    var view: NativeAdView? = null
        private set

    val isReady: Boolean get() = view != null

    private val readyListener = Runnable { load() }

    /** Starts loading as soon as the SDK is up (immediately if it already is). */
    fun start() {
        if (destroyed) return
        AdsManager.whenReady(readyListener)
    }

    private fun alive() = !destroyed && !activity.isFinishing && !activity.isDestroyed

    private fun load() {
        if (!alive() || loading || isReady) return
        loading = true
        try {
            val l = loader ?: NativeAdLoader(activity).also { loader = it }
            l.loadAd(
                AdRequest.Builder(AdConfig.nativeAdUnitId).build(),
                object : NativeAdLoadListener {
                    override fun onAdLoaded(nativeAd: NativeAd) {
                        loading = false
                        if (!alive()) return
                        bind(nativeAd)
                    }

                    override fun onAdFailedToLoad(adRequestError: AdRequestError) {
                        loading = false
                        Log.i(TAG, "native failed: code=${adRequestError.code} msg=${adRequestError.description}")
                        scheduleRetry()
                    }
                }
            )
        } catch (t: Throwable) {
            loading = false
            Log.w(TAG, "native crashed: ${t.javaClass.simpleName}")
        }
    }

    private fun bind(ad: NativeAd) {
        try {
            val v = LayoutInflater.from(activity).inflate(R.layout.view_native_ad, null, false) as NativeAdView
            val binder = NativeAdViewBinder.Builder(v)
                .setAgeView(v.findViewById<TextView>(R.id.native_age))
                .setBodyView(v.findViewById<TextView>(R.id.native_body))
                .setCallToActionView(v.findViewById<Button>(R.id.native_cta))
                .setDomainView(v.findViewById<TextView>(R.id.native_domain))
                .setFeedbackView(v.findViewById<ImageView>(R.id.native_feedback))
                .setIconView(v.findViewById<ImageView>(R.id.native_icon))
                .setMediaView(v.findViewById<MediaView>(R.id.native_media))
                .setSponsoredView(v.findViewById<TextView>(R.id.native_sponsored))
                .setTitleView(v.findViewById<TextView>(R.id.native_title))
                .setWarningView(v.findViewById<TextView>(R.id.native_warning))
                .build()
            val result = ad.bindNativeAd(binder)
            // bindNativeAd reports success/failure as a result object; judged by name so this file does not depend on its exact type.
            if (result.toString().contains("Failure", ignoreCase = true) || result.javaClass.simpleName.contains("Failure")) {
                Log.i(TAG, "native bind failed: $result")
                scheduleRetry()
                return
            }
            nativeAd = ad
            view = v
            onChanged()
        } catch (t: Throwable) {
            Log.w(TAG, "native bind crashed: ${t.javaClass.simpleName}")
            scheduleRetry()
        }
    }

    /** Puts the ad view into [container] (moving it out of any previous parent). No-op while there is no ad. */
    fun attachTo(container: ViewGroup) {
        val v = view ?: return
        if (v.parent === container) return
        (v.parent as? ViewGroup)?.removeView(v)
        container.removeAllViews()
        container.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun scheduleRetry() {
        if (retries >= AdConfig.RETRY_MAX_TRIES || !alive()) return
        val delay = AdConfig.RETRY_BASE_MS shl retries
        retries++
        main.postDelayed({ load() }, delay)
    }

    fun destroy() {
        destroyed = true
        AdsManager.removeReadyListener(readyListener)
        main.removeCallbacksAndMessages(null)
        (view?.parent as? ViewGroup)?.removeView(view)
        view = null
        nativeAd = null
        loader = null
    }

    private companion object {
        const val TAG = "FastVPN-Ads"
    }
}
