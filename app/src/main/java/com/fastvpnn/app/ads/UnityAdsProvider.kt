package com.fastvpnn.app.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.unity3d.ads.BannerAd
import com.unity3d.ads.BannerLoadConfiguration
import com.unity3d.ads.BannerLoadListener
import com.unity3d.ads.BannerShowListener
import com.unity3d.ads.BannerSize
import com.unity3d.ads.InitializationConfiguration
import com.unity3d.ads.InitializationListener
import com.unity3d.ads.InterstitialAd as UnityInterstitial
import com.unity3d.ads.InterstitialLoadListener
import com.unity3d.ads.InterstitialShowListener
import com.unity3d.ads.LoadConfiguration
import com.unity3d.ads.LogLevel
import com.unity3d.ads.RewardedAd as UnityRewarded
import com.unity3d.ads.RewardedLoadListener
import com.unity3d.ads.RewardedShowListener
import com.unity3d.ads.ShowConfiguration
import com.unity3d.ads.ShowFinishState
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsError
import com.unity3d.ads.UnityAdsExperimental

/**
 * Unity Ads implementation (SDK 4.20 API: InitializationConfiguration / LoadConfiguration / ShowConfiguration).
 * Unity Ads has no native-ad format, so hasNative / hasNativeBanner are false.
 * All Unity-specific code is in this one file.
 */
internal class UnityAdsProvider : AdProvider {

    override val network = AdNetwork.UNITY
    private var state = InitState.NOT_STARTED
    override val initState: InitState get() = state

    private val ids get() = AdConfig.unity
    private fun placement(id: String) = AdConfig.unityPlacement(id)

    override val hasBanner get() = placement(ids.bannerPlacementId) != null
    override val hasInterstitial get() = placement(ids.interstitialPlacementId) != null
    override val hasRewarded get() = placement(ids.rewardedPlacementId) != null
    override val hasNative = false
    override val hasNativeBanner = false

    private var banner: BannerAd? = null
    private var bannerContainer: ViewGroup? = null

    private var interstitial: UnityInterstitial? = null
    private val interstitialGate = LoadGate()

    private var rewarded: UnityRewarded? = null
    private val rewardedGate = LoadGate()

    // ---------------- init / privacy ----------------

    override fun applyPrivacy(limited: Boolean) {
        try {
            UnityAds.userConsent = !limited
            UnityAds.nonBehavioral = limited
        } catch (t: Throwable) {
            AdLog.w("Unity privacy flags failed: ${t.javaClass.simpleName}")
        }
    }

    @OptIn(UnityAdsExperimental::class)
    override fun initialize(app: Application, onDone: (Boolean) -> Unit) {
        if (state != InitState.NOT_STARTED) return
        val gameId = ids.gameId
        if (gameId.isBlank()) {
            state = InitState.FAILED
            onDone(false)
            return
        }
        state = InitState.INITIALIZING
        try {
            val config = InitializationConfiguration.Builder(gameId)
                .withTestMode(AdConfig.isTestMode)
                .withLogLevel(if (AdConfig.isTestMode) LogLevel.DEBUG else LogLevel.ERROR)
                .build()
            UnityAds.initialize(config, InitializationListener { error ->
                val ok = error == null
                state = if (ok) InitState.READY else InitState.FAILED
                AdLog.d("Unity initialized: success=$ok")
                onDone(ok)
            })
        } catch (t: Throwable) {
            state = InitState.FAILED
            AdLog.w("Unity init crashed: ${t.javaClass.simpleName}")
            onDone(false)
        }
    }

    // ---------------- banner ----------------

    override fun loadBanner(activity: Activity, container: ViewGroup, onResult: (Boolean) -> Unit) {
        val id = placement(ids.bannerPlacementId)
        if (id == null) { onResult(false); return }
        destroyBanner()
        try {
            AdLog.d("Unity banner loading")
            val config = BannerLoadConfiguration.Builder(id, BannerSize(320, 50))
                .withListener(object : BannerShowListener {
                    override fun onBannerShown(bannerAd: BannerAd) {}
                    override fun onBannerClicked(bannerAd: BannerAd) {}
                    override fun onBannerFailedToShow(bannerAd: BannerAd, error: UnityAdsError) {
                        AdLog.d("Unity banner failed to show")
                    }
                })
                .build()
            BannerAd.load(config, BannerLoadListener { bannerAd, error ->
                val view = bannerAd?.view
                if (bannerAd == null || view == null || activity.isFinishing || activity.isDestroyed) {
                    AdLog.d("Unity banner failed: ${error?.code}")
                    onResult(false)
                } else {
                    banner = bannerAd
                    bannerContainer = container
                    container.removeAllViews()
                    container.addView(
                        view,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
                    )
                    AdLog.d("Unity banner loaded")
                    onResult(true)
                }
            })
        } catch (t: Throwable) {
            AdLog.w("Unity banner crashed: ${t.javaClass.simpleName}")
            onResult(false)
        }
    }

    override fun destroyBanner() {
        try {
            banner?.view?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        } catch (_: Throwable) {}
        banner = null
        bannerContainer = null
    }

    // ---------------- interstitial ----------------

    override fun loadInterstitial(context: Context) {
        val id = placement(ids.interstitialPlacementId) ?: return
        if (isInterstitialReady() || !interstitialGate.tryStart()) return
        try {
            AdLog.d("Unity interstitial loading")
            UnityInterstitial.load(LoadConfiguration.Builder(id).build(), InterstitialLoadListener { ad, error ->
                if (ad != null) {
                    interstitial = ad
                    interstitialGate.success()
                    AdLog.d("Unity interstitial loaded")
                } else {
                    interstitial = null
                    interstitialGate.failure()
                    AdLog.d("Unity interstitial failed: ${error?.code}")
                }
            })
        } catch (t: Throwable) {
            interstitialGate.failure()
            AdLog.w("Unity interstitial load crashed: ${t.javaClass.simpleName}")
        }
    }

    override fun isInterstitialReady() = interstitial != null

    override fun showInterstitial(activity: Activity, callbacks: FullScreenCallbacks) {
        val ad = interstitial
        if (ad == null) { callbacks.onFailed(); return }
        interstitial = null // a Unity ad can be shown once
        try {
            ad.show(ShowConfiguration.Builder().build(), object : InterstitialShowListener {
                override fun onStarted(unityAd: UnityInterstitial) { callbacks.onDisplayed() }
                override fun onClicked(unityAd: UnityInterstitial) {}
                override fun onCompleted(unityAd: UnityInterstitial, state: ShowFinishState) { callbacks.onClosed() }
                override fun onFailed(unityAd: UnityInterstitial, error: UnityAdsError) {
                    AdLog.d("Unity interstitial show failed: ${error.code}")
                    callbacks.onFailed()
                }
            })
        } catch (t: Throwable) {
            callbacks.onFailed()
        }
    }

    // ---------------- rewarded ----------------

    override fun loadRewarded(context: Context) {
        val id = placement(ids.rewardedPlacementId) ?: return
        if (isRewardedReady() || !rewardedGate.tryStart()) return
        try {
            AdLog.d("Unity rewarded loading")
            UnityRewarded.load(LoadConfiguration.Builder(id).build(), RewardedLoadListener { ad, error ->
                if (ad != null) {
                    rewarded = ad
                    rewardedGate.success()
                    AdLog.d("Unity rewarded loaded")
                } else {
                    rewarded = null
                    rewardedGate.failure()
                    AdLog.d("Unity rewarded failed: ${error?.code}")
                }
            })
        } catch (t: Throwable) {
            rewardedGate.failure()
            AdLog.w("Unity rewarded load crashed: ${t.javaClass.simpleName}")
        }
    }

    override fun isRewardedReady() = rewarded != null

    override fun showRewarded(activity: Activity, callbacks: FullScreenCallbacks) {
        val ad = rewarded
        if (ad == null) { callbacks.onFailed(); return }
        rewarded = null
        try {
            ad.show(ShowConfiguration.Builder().build(), object : RewardedShowListener {
                override fun onStarted(unityAd: UnityRewarded) { callbacks.onDisplayed() }
                override fun onClicked(unityAd: UnityRewarded) {}
                // The reward is granted ONLY here, when Unity confirms the user earned it.
                override fun onRewarded(unityAd: UnityRewarded) {
                    AdLog.d("Unity reward earned")
                    callbacks.onRewarded?.invoke()
                }
                override fun onCompleted(unityAd: UnityRewarded, state: ShowFinishState) { callbacks.onClosed() }
                override fun onFailed(unityAd: UnityRewarded, error: UnityAdsError) {
                    AdLog.d("Unity rewarded show failed: ${error.code}")
                    callbacks.onFailed()
                }
            })
        } catch (t: Throwable) {
            callbacks.onFailed()
        }
    }

    // ---------------- native: not offered by Unity Ads ----------------

    override fun loadNative(context: Context, onLoaded: (NativeAdHandle) -> Unit, onFailed: () -> Unit) = onFailed()
    override fun loadNativeBanner(activity: Activity, container: ViewGroup, onResult: (Boolean) -> Unit) = onResult(false)
    override fun destroyNativeBanner() {}
}
