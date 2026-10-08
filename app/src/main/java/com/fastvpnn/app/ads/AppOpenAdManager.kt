package com.fastvpnn.app.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import com.fastvpnn.app.ui.ConsentActivity
import com.fastvpnn.app.ui.SplashActivity
import java.lang.ref.WeakReference

/**
 * "App open" style ad: neither Meta nor Unity has a dedicated App Open format, so this reuses the
 * interstitial pipeline (AdsManager) when the user comes BACK to the app after it was in the
 * background for AdConfig.APP_OPEN_MIN_BACKGROUND_MS. Never on a cold start, never over the splash or
 * consent screens, and subject to the same frequency caps as every other full-screen ad.
 */
internal object AppOpenAdManager : Application.ActivityLifecycleCallbacks {

    private var startedActivityCount = 0
    private var backgroundedAt = 0L // 0 = not backgrounded yet (cold start)
    private var currentActivity: WeakReference<Activity>? = null
    private var registered = false

    fun attach(application: Application) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(this)
    }

    private fun maybeShow() {
        val activity = currentActivity?.get() ?: return
        if (activity is SplashActivity || activity is ConsentActivity) return
        if (backgroundedAt == 0L || SystemClock.elapsedRealtime() - backgroundedAt < AdConfig.APP_OPEN_MIN_BACKGROUND_MS) return
        AdsManager.showAppOpen(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivityCount == 0) maybeShow()
        startedActivityCount++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        if (startedActivityCount == 0) backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onActivityResumed(activity: Activity) { currentActivity = WeakReference(activity) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
