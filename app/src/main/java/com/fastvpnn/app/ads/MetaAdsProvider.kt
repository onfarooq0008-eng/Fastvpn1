package com.fastvpnn.app.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.facebook.ads.Ad
import com.facebook.ads.AdError
import com.facebook.ads.AdListener
import com.facebook.ads.AdOptionsView
import com.facebook.ads.AdSettings
import com.facebook.ads.AdSize
import com.facebook.ads.AdView
import com.facebook.ads.AudienceNetworkAds
import com.facebook.ads.InterstitialAd
import com.facebook.ads.InterstitialAdListener
import com.facebook.ads.NativeAd
import com.facebook.ads.NativeAdListener
import com.facebook.ads.NativeBannerAd
import com.facebook.ads.NativeBannerAdView
import com.facebook.ads.RewardedVideoAd
import com.facebook.ads.RewardedVideoAdListener
import com.fastvpnn.app.databinding.ItemNativeAdBinding

/** "code=1001 msg=..." so a no-fill / wrong-placement problem can be read straight from the log. */
private fun describe(e: AdError?): String = "code=${e?.errorCode} msg=${e?.errorMessage}"

/** Meta Audience Network implementation. All calls on the main thread. */
internal class MetaAdsProvider : AdProvider {

    override val network = AdNetwork.META
    private var state = InitState.NOT_STARTED
    override val initState: InitState get() = state

    private val ids get() = AdConfig.meta
    private fun placement(id: String) = AdConfig.metaPlacement(id)

    override val hasBanner get() = placement(ids.bannerId) != null
    override val hasInterstitial get() = placement(ids.interstitialId) != null
    override val hasRewarded get() = placement(ids.rewardedId) != null
    override val hasNative get() = placement(ids.nativeId) != null
    override val hasNativeBanner get() = placement(ids.nativeBannerId) != null

    private var banner: AdView? = null
    private var nativeBanner: NativeBannerAd? = null

    private var interstitial: InterstitialAd? = null
    private val interstitialGate = LoadGate()
    private var interstitialCallbacks: FullScreenCallbacks? = null

    private var rewarded: RewardedVideoAd? = null
    private val rewardedGate = LoadGate()
    private var rewardedCallbacks: FullScreenCallbacks? = null
    private var rewardEarned = false

    // ---------------- init / privacy ----------------

    override fun applyPrivacy(limited: Boolean) {
        try {
            // Meta's documented US-state privacy switch. Empty array = normal, "LDU" = Limited Data Use.
            AdSettings.setDataProcessingOptions(if (limited) arrayOf("LDU") else emptyArray<String>())
        } catch (t: Throwable) {
            AdLog.w("Meta privacy flags failed: ${t.javaClass.simpleName}")
        }
    }

    override fun initialize(app: Application, onDone: (Boolean) -> Unit) {
        if (state != InitState.NOT_STARTED) return
        state = InitState.INITIALIZING
        try {
            AudienceNetworkAds.buildInitSettings(app)
                .withInitListener { result ->
                    state = if (result.isSuccess) InitState.READY else InitState.FAILED
                    AdLog.d("Meta initialized: success=${result.isSuccess}")
                    onDone(result.isSuccess)
                }
                .initialize()
        } catch (t: Throwable) {
            state = InitState.FAILED
            AdLog.w("Meta init crashed: ${t.javaClass.simpleName}")
            onDone(false)
        }
    }

    // ---------------- banner ----------------

    override fun loadBanner(activity: Activity, container: ViewGroup, onResult: (Boolean) -> Unit) {
        val id = placement(ids.bannerId)
        if (id == null) { onResult(false); return }
        destroyBanner()
        try {
            val view = AdView(activity, id, AdSize.BANNER_HEIGHT_50)
            banner = view
            container.removeAllViews()
            container.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
            )
            AdLog.d("Meta banner loading")
            val listener = object : AdListener {
                override fun onError(ad: Ad?, error: AdError?) {
                    AdLog.d("Meta banner failed: ${describe(error)}")
                    if (banner === view) destroyBanner()
                    onResult(false)
                }
                override fun onAdLoaded(ad: Ad?) { AdLog.d("Meta banner loaded"); onResult(true) }
                override fun onAdClicked(ad: Ad?) {}
                override fun onLoggingImpression(ad: Ad?) {}
            }
            view.loadAd(view.buildLoadAdConfig().withAdListener(listener).build())
        } catch (t: Throwable) {
            AdLog.w("Meta banner crashed: ${t.javaClass.simpleName}")
            destroyBanner()
            onResult(false)
        }
    }

    override fun destroyBanner() {
        try { banner?.destroy() } catch (_: Throwable) {}
        banner = null
    }

    // ---------------- interstitial ----------------

    override fun loadInterstitial(context: Context) {
        val id = placement(ids.interstitialId) ?: return
        if (isInterstitialReady() || !interstitialGate.tryStart()) return
        try {
            AdLog.d("Meta interstitial loading")
            val ad = InterstitialAd(context.applicationContext, id)
            val listener = object : InterstitialAdListener {
                override fun onAdLoaded(loaded: Ad?) {
                    interstitial = ad
                    interstitialGate.success()
                    AdLog.d("Meta interstitial loaded")
                }
                override fun onError(failed: Ad?, error: AdError?) {
                    AdLog.d("Meta interstitial error: ${describe(error)}")
                    val cb = interstitialCallbacks
                    if (cb != null) { // happened while showing
                        interstitialCallbacks = null
                        interstitial = null
                        cb.onFailed()
                    } else {
                        interstitialGate.failure()
                    }
                }
                override fun onInterstitialDisplayed(shown: Ad?) { interstitialCallbacks?.let { it.onDisplayed() } }
                override fun onInterstitialDismissed(dismissed: Ad?) {
                    val cb = interstitialCallbacks
                    interstitialCallbacks = null
                    interstitial = null
                    cb?.let { it.onClosed() }
                }
                override fun onAdClicked(clicked: Ad?) {}
                override fun onLoggingImpression(impression: Ad?) {}
            }
            ad.loadAd(ad.buildLoadAdConfig().withAdListener(listener).build())
        } catch (t: Throwable) {
            interstitialGate.failure()
            AdLog.w("Meta interstitial load crashed: ${t.javaClass.simpleName}")
        }
    }

    override fun isInterstitialReady(): Boolean {
        val ad = interstitial ?: return false
        return ad.isAdLoaded && !ad.isAdInvalidated
    }

    override fun showInterstitial(activity: Activity, callbacks: FullScreenCallbacks) {
        val ad = interstitial
        if (ad == null || !isInterstitialReady()) { callbacks.onFailed(); return }
        interstitialCallbacks = callbacks
        try {
            if (!ad.show()) {
                interstitialCallbacks = null
                interstitial = null
                callbacks.onFailed()
            }
        } catch (t: Throwable) {
            interstitialCallbacks = null
            interstitial = null
            callbacks.onFailed()
        }
    }

    // ---------------- rewarded ----------------

    override fun loadRewarded(context: Context) {
        val id = placement(ids.rewardedId) ?: return
        if (isRewardedReady() || !rewardedGate.tryStart()) return
        try {
            AdLog.d("Meta rewarded loading")
            val ad = RewardedVideoAd(context.applicationContext, id)
            val listener = object : RewardedVideoAdListener {
                override fun onAdLoaded(loaded: Ad?) {
                    rewarded = ad
                    rewardedGate.success()
                    AdLog.d("Meta rewarded loaded")
                }
                override fun onError(failed: Ad?, error: AdError?) {
                    AdLog.d("Meta rewarded error: ${describe(error)}")
                    val cb = rewardedCallbacks
                    if (cb != null) { rewardedCallbacks = null; rewarded = null; cb.onFailed() } else rewardedGate.failure()
                }
                override fun onLoggingImpression(impression: Ad?) { rewardedCallbacks?.let { it.onDisplayed() } }
                override fun onRewardedVideoCompleted() {
                    // Meta confirms the user watched the video: this is the only place a reward is granted.
                    rewardEarned = true
                    AdLog.d("Meta reward earned")
                    rewardedCallbacks?.onRewarded?.invoke()
                }
                override fun onRewardedVideoClosed() {
                    val cb = rewardedCallbacks
                    rewardedCallbacks = null
                    rewarded = null
                    rewardEarned = false
                    cb?.let { it.onClosed() }
                }
                override fun onAdClicked(clicked: Ad?) {}
            }
            ad.loadAd(ad.buildLoadAdConfig().withAdListener(listener).build())
        } catch (t: Throwable) {
            rewardedGate.failure()
            AdLog.w("Meta rewarded load crashed: ${t.javaClass.simpleName}")
        }
    }

    override fun isRewardedReady(): Boolean {
        val ad = rewarded ?: return false
        return ad.isAdLoaded && !ad.isAdInvalidated
    }

    override fun showRewarded(activity: Activity, callbacks: FullScreenCallbacks) {
        val ad = rewarded
        if (ad == null || !isRewardedReady()) { callbacks.onFailed(); return }
        rewardedCallbacks = callbacks
        rewardEarned = false
        try {
            if (!ad.show()) { rewardedCallbacks = null; rewarded = null; callbacks.onFailed() }
        } catch (t: Throwable) {
            rewardedCallbacks = null; rewarded = null; callbacks.onFailed()
        }
    }

    // ---------------- native ----------------

    override fun loadNative(context: Context, onLoaded: (NativeAdHandle) -> Unit, onFailed: () -> Unit) {
        val id = placement(ids.nativeId)
        if (id == null) { onFailed(); return }
        try {
            val nativeAd = NativeAd(context.applicationContext, id)
            val listener = object : NativeAdListener {
                override fun onMediaDownloaded(ad: Ad?) {}
                override fun onError(ad: Ad?, error: AdError?) {
                    AdLog.d("Meta native failed: ${describe(error)}")
                    onFailed()
                }
                override fun onAdLoaded(ad: Ad?) {
                    if (ad !== nativeAd || nativeAd.isAdInvalidated) { onFailed(); return }
                    AdLog.d("Meta native loaded")
                    onLoaded(MetaNativeAdHandle(nativeAd))
                }
                override fun onAdClicked(ad: Ad?) {}
                override fun onLoggingImpression(ad: Ad?) {}
            }
            nativeAd.loadAd(nativeAd.buildLoadAdConfig().withAdListener(listener).build())
        } catch (t: Throwable) {
            AdLog.w("Meta native crashed: ${t.javaClass.simpleName}")
            onFailed()
        }
    }

    // ---------------- native banner ----------------

    override fun loadNativeBanner(activity: Activity, container: ViewGroup, onResult: (Boolean) -> Unit) {
        val id = placement(ids.nativeBannerId)
        if (id == null) { onResult(false); return }
        destroyNativeBanner()
        try {
            val ad = NativeBannerAd(activity, id)
            nativeBanner = ad
            val listener = object : NativeAdListener {
                override fun onMediaDownloaded(loaded: Ad?) {}
                override fun onError(failed: Ad?, error: AdError?) {
                    AdLog.d("Meta native banner failed: ${describe(error)}")
                    if (nativeBanner === ad) destroyNativeBanner()
                    onResult(false)
                }
                override fun onAdLoaded(loaded: Ad?) {
                    if (nativeBanner !== ad || ad.isAdInvalidated || activity.isFinishing || activity.isDestroyed) {
                        onResult(false)
                        return
                    }
                    container.removeAllViews()
                    // Meta's template renders AdChoices, "Sponsored", icon, headline and CTA for us.
                    container.addView(NativeBannerAdView.render(activity, ad, NativeBannerAdView.Type.HEIGHT_100))
                    AdLog.d("Meta native banner loaded")
                    onResult(true)
                }
                override fun onAdClicked(clicked: Ad?) {}
                override fun onLoggingImpression(impression: Ad?) {}
            }
            ad.loadAd(ad.buildLoadAdConfig().withAdListener(listener).build())
        } catch (t: Throwable) {
            AdLog.w("Meta native banner crashed: ${t.javaClass.simpleName}")
            destroyNativeBanner()
            onResult(false)
        }
    }

    override fun destroyNativeBanner() {
        try { nativeBanner?.destroy() } catch (_: Throwable) {}
        nativeBanner = null
    }
}

/** Meta native ad rendered into the shared item_native_ad layout. */
internal class MetaNativeAdHandle(private val ad: NativeAd) : NativeAdHandle {
    override val isValid: Boolean get() = ad.isAdLoaded && !ad.isAdInvalidated

    override fun render(binding: ItemNativeAdBinding) {
        ad.unregisterView()
        binding.adHeadline.text = ad.advertiserName.orEmpty()
        binding.adBody.text = ad.adBodyText.orEmpty()
        binding.adSponsored.text = ad.sponsoredTranslation ?: "Sponsored"
        if (ad.hasCallToAction()) {
            binding.adCallToAction.text = ad.adCallToAction
            binding.adCallToAction.visibility = View.VISIBLE
        } else {
            binding.adCallToAction.visibility = View.GONE
        }
        binding.adChoicesContainer.removeAllViews()
        binding.adChoicesContainer.addView(AdOptionsView(binding.root.context, ad, binding.root), 0)
        ad.registerViewForInteraction(binding.root, binding.adMedia, binding.adIcon, listOf<View>(binding.adCallToAction))
    }

    override fun destroy() {
        try { ad.unregisterView(); ad.destroy() } catch (_: Throwable) {}
    }
}
