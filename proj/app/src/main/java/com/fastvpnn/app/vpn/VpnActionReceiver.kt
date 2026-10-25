package com.fastvpnn.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fastvpnn.app.util.NotificationHelper
import com.fastvpnn.app.data.ServerSource
import com.fastvpnn.app.util.SecureKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Handles the "Disconnect" button on the persistent connected notification --
 * this can fire even if MainActivity isn't currently open, so it talks to
 * VpnTunnelManager directly rather than routing through the Activity. Uses
 * VpnTunnelManagerHolder to get the SAME shared instance the rest of the app
 * uses -- GoBackend tracks which tunnel handle is running as private instance
 * state, so a brand new GoBackend created here wouldn't actually have a real
 * handle to tear down, even after telling it the tunnel is up.
 *
 * NOT YET VERIFIED against a real process-death scenario on a physical
 * device. See MANUAL_TESTING.md and the logging below -- when you run that
 * checklist, filter logcat by tag "FastVPN-Tunnel" and "FastVPN-Disconnect"
 * to confirm each step (tunnel torn down, backend peer removed, notification
 * cleared) actually happened, rather than just observing the end UI state.
 */
class VpnActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISCONNECT = "com.fastvpnn.app.ACTION_DISCONNECT"
        private const val TAG = "FastVPN-Disconnect"

        // A broadcast receiver kept alive with goAsync() must finish within ~10 s or it risks an ANR.
        // The local teardown is quick; the backend call is best-effort and is retried on next launch.
        private const val UNREGISTER_TIMEOUT_MS = 6_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISCONNECT) return
        Log.d(TAG, "onReceive: disconnect action from notification")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tunnelManager = VpnTunnelManagerHolder.get(context)
                // Do not manufacture a Tunnel object from the Android VPN UI
                // state. GoBackend owns the real Tunnel object and only that
                // object can be used to tear down the userspace WireGuard
                // tunnel. The holder gives this receiver the same manager
                // instance used by MainActivity.
                val stateBefore = tunnelManager.syncStateFromBackend()
                Log.d(TAG, "state before disconnect: $stateBefore")
                val result = tunnelManager.disconnect()
                if (result.isSuccess) {
                    Log.d(TAG, "local tunnel teardown: success")
                    val appContext = context.applicationContext
                    // Remove the notification the moment the tunnel is down. It used to be cleared only
                    // AFTER the key store was opened and the backend unregister call finished (up to
                    // several seconds), so the notification lingered after the VPN was already off.
                    NotificationHelper.clear(appContext)
                    Log.d(TAG, "notification cleared")
                    val keyStore = SecureKeyStore.get(appContext)
                    val serverSource = ServerSource(appContext)
                    val active = keyStore.activeRegistration()
                    if (active != null) {
                        try {
                            withTimeout(UNREGISTER_TIMEOUT_MS) {
                                serverSource.unregister(keyStore.clientPublicKeyBase64(), active.serverId, active.token)
                            }
                            keyStore.clearActiveRegistration()
                            Log.d(TAG, "backend peer unregistered for server ${active.serverId}")
                        } catch (e: Exception) {
                            // Keep the encrypted lease so the next app launch can retry cleanup.
                            Log.e(TAG, "backend unregister failed -- will retry on next app launch", e)
                        }
                    } else {
                        Log.d(TAG, "no active registration lease found to unregister")
                    }
                } else {
                    // Local teardown failed -- deliberately leave the notification and
                    // backend registration alone, since the tunnel may genuinely still
                    // be up. Clearing them here would desync the UI from reality.
                    Log.e(TAG, "local tunnel teardown FAILED, leaving state as-is", result.exceptionOrNull())
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
