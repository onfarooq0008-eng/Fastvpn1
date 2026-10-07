package com.fastvpnn.app.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import java.lang.ref.WeakReference

/**
 * Preloads an App Open ad and shows it whenever the app comes back to the foreground
 * (cold start or returning from background) -- Google's recommended pattern, kept dependency-free
 * by tracking foreground state off Application.ActivityLifecycleCallbacks instead of
 * pulling in androidx.lifecycle.process.
 */
object AppOpenAdManager : Application.ActivityLifecycleCallbacks {

    private const val TAG = "FastVPN-AdMob"
    // Google's own guidance: don't hold on to an app open ad for more than ~4 hours.
    private const val AD_MAX_AGE_MS = 4L * 60 * 60 * 1000

    private var appOpenAd: AppOpenAd? = null
    private var adLoadedAtMs: Long = 0L
    private var isLoadingAd = false
    private var isShowingAd = false

    private var startedActivityCount = 0
    private var currentActivity: WeakReference<Activity>? = null
    private var isFirstShow = true
    private var registered = false

    fun attach(application: Application) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(this)
    }

    fun loadAd(context: Context) {
        if (isLoadingAd || isAdAvailable()) return
        isLoadingAd = true
        AppOpenAd.load(
            context.applicationContext,
            AdManager.APP_OPEN_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    adLoadedAtMs = SystemClock.elapsedRealtime()
                    isLoadingAd = false
                    Log.d(TAG, "App open ad loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingAd = false
                    Log.e(TAG, "App open ad failed: code=${error.code}, domain=${error.domain}, message=${error.message}")
                }
            }
        )
    }

    private fun isAdAvailable(): Boolean {
        val ad = appOpenAd ?: return false
        val ageMs = SystemClock.elapsedRealtime() - adLoadedAtMs
        return ageMs < AD_MAX_AGE_MS
    }

    private fun showAdIfAvailable() {
        if (isShowingAd) return

        // Skip the very first foreground transition right after cold start so the app open
        // ad never fights the SplashActivity for the screen; MainActivity is a better beat.
        if (isFirstShow) {
            isFirstShow = false
            loadAd(currentActivity?.get()?.applicationContext ?: return)
            return
        }

        val activity = currentActivity?.get()
        val ad = appOpenAd
        if (activity == null || ad == null || !isAdAvailable()) {
            activity?.let { loadAd(it.applicationContext) }
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                isShowingAd = false
                loadAd(activity.applicationContext)
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                appOpenAd = null
                isShowingAd = false
                Log.e(TAG, "App open ad failed to show: ${error.message}")
                loadAd(activity.applicationContext)
            }
            override fun onAdShowedFullScreenContent() {
                isShowingAd = true
            }
        }
        ad.show(activity)
    }

    // ---------------- Application.ActivityLifecycleCallbacks ----------------
    // Manual foreground/background tracking: startedActivityCount going 0 -> 1 means the app
    // just came to the foreground (equivalent to ProcessLifecycleOwner's ON_START).

    override fun onActivityStarted(activity: Activity) {
        if (startedActivityCount == 0) {
            showAdIfAvailable()
        }
        startedActivityCount++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
