package com.fastvpnn.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions

/** Centralized AdMob manager for all ad formats used in the app. */
object AdManager {
    private const val TAG = "FastVPN-AdMob"

    private const val BANNER_AD_UNIT_ID = "ca-app-pub-3171485884518174/8991004479"
    private const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3171485884518174/5968772815"
    private const val NATIVE_AD_UNIT_ID = "ca-app-pub-3171485884518174/3342609470"
    const val APP_OPEN_AD_UNIT_ID = "ca-app-pub-3171485884518174/1454812730"

    @Volatile private var initialized = false
    @Volatile private var initStarted = false
    @Volatile private var interstitialAd: InterstitialAd? = null

    fun init(context: Context) {
        if (initStarted) return
        synchronized(this) {
            if (initStarted) return
            initStarted = true
            MobileAds.initialize(context.applicationContext) { status ->
                initialized = true
                Log.d(TAG, "Mobile Ads initialized: $status")
                preloadInterstitial(context.applicationContext)
                AppOpenAdManager.loadAd(context.applicationContext)
            }
        }
    }

    // ---------------- Banner ----------------

    fun loadBanner(container: FrameLayout, activity: Activity) {
        init(activity)
        container.removeAllViews()
        val adView = AdView(activity)
        adView.adUnitId = BANNER_AD_UNIT_ID
        adView.setAdSize(AdSize.BANNER)
        adView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                Log.d(TAG, "Banner loaded")
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.e(TAG, "Banner failed: code=${error.code}, domain=${error.domain}, message=${error.message}")
            }
        }
        container.addView(adView)
        adView.post { adView.loadAd(AdRequest.Builder().build()) }
    }

    // ---------------- Interstitial ----------------

    fun preloadInterstitial(context: Context) {
        InterstitialAd.load(
            context.applicationContext,
            INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    Log.d(TAG, "Interstitial loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    Log.e(TAG, "Interstitial failed: code=${error.code}, domain=${error.domain}, message=${error.message}")
                }
            }
        )
    }

    fun maybeShowInterstitial(activity: Activity, onDismissed: () -> Unit) {
        val ad = interstitialAd
        if (ad == null) {
            preloadInterstitial(activity)
            onDismissed()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                preloadInterstitial(activity)
                onDismissed()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAd = null
                Log.e(TAG, "Interstitial failed to show: ${error.message}")
                preloadInterstitial(activity)
                onDismissed()
            }
        }
        ad.show(activity)
    }

    // ---------------- Native ----------------

    /**
     * Loads a single native ad and hands it back via [onLoaded]. Caller owns the returned
     * [NativeAd] and is responsible for calling [NativeAd.destroy] once the view showing it
     * is recycled/torn down (see HomeListAdapter's native ad view holder).
     */
    fun loadNativeAd(context: Context, onLoaded: (NativeAd) -> Unit, onFailed: (() -> Unit)? = null) {
        val loader = com.google.android.gms.ads.AdLoader.Builder(context.applicationContext, NATIVE_AD_UNIT_ID)
            .forNativeAd { nativeAd -> onLoaded(nativeAd) }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.e(TAG, "Native ad failed: code=${error.code}, domain=${error.domain}, message=${error.message}")
                    onFailed?.invoke()
                }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .build()
        loader.loadAd(AdRequest.Builder().build())
    }
}
