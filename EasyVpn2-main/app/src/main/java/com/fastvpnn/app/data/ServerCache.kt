package com.fastvpnn.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Holds one in-flight server fetch so the home screen can open with a warm
 * list instead of starting the network call from scratch.
 *
 * [SplashActivity] calls [warm] right as the logo is shown, which starts the
 * fetch on a process-level scope (NOT the Activity's lifecycleScope -- Splash
 * finishes itself immediately after navigating on, which would otherwise
 * cancel the request before it completes). [MainActivity] calls [take] once
 * on its own initial load to consume that same in-flight/completed result;
 * every load after that (pull-to-refresh, the 20s background tick) goes
 * through ServerSource/the network as normal.
 */
object ServerCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var prefetch: Deferred<List<Server>>? = null

    /** Starts fetching servers now; safe to call multiple times (e.g. if Splash
     *  somehow runs twice) since each call just replaces the pending one. */
    fun warm(context: Context) {
        val appContext = context.applicationContext
        prefetch = scope.async { ServerSource(appContext).getServers() }
    }

    /** Returns the pending/completed prefetch and clears it -- a one-shot
     *  handoff so only the very first load after Splash uses it. */
    fun take(): Deferred<List<Server>>? {
        val result = prefetch
        prefetch = null
        return result
    }
}
