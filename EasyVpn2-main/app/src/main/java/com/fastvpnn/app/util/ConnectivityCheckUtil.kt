package com.fastvpnn.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The tunnel coming up (GoBackend reporting success) only means the local
 * network interface was created -- it does NOT mean the remote WireGuard
 * peer actually answered a handshake, or that the server can route traffic
 * to the real internet (this is exactly the "connects but no internet" class
 * of bug: subnet collisions, missing FORWARD rules, IP forwarding disabled).
 *
 * This performs a real, executed check rather than trusting the interface
 * state: opens a TCP socket explicitly bound to the VPN's own Network object
 * via Network.bindSocket() -- the officially documented way to force traffic
 * through a specific network (see developer.android.com/develop/connectivity/vpn),
 * needed because Android excludes the VPN app's own traffic from its tunnel
 * by default, so an unbound socket would silently test the real network
 * instead and always report success even when the tunnel is completely dead.
 */
object ConnectivityCheckUtil {

    /**
     * ConnectivityManager doesn't necessarily know about a just-established VPN
     * network the instant GoBackend.setState() returns -- there's a short OS-level
     * propagation delay before it shows up in cm.allNetworks. Checking once and
     * giving up immediately if it's not there yet was making failures look
     * near-instant regardless of the socket timeout, since the socket connect
     * attempt never even got a chance to run. This polls briefly instead.
     */
    private suspend fun findVpnNetwork(context: Context, maxWaitMs: Long = 3_000): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (true) {
            val vpnNetwork = cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            if (vpnNetwork != null) return vpnNetwork
            if (System.currentTimeMillis() >= deadline) return null
            delay(150)
        }
    }

    suspend fun verifyInternetThroughVpn(context: Context, timeoutMs: Int = 5000): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val vpnNetwork = findVpnNetwork(context) ?: return@withContext false
                Socket().use { socket ->
                    vpnNetwork.bindSocket(socket)
                    socket.connect(InetSocketAddress("1.1.1.1", 443), timeoutMs)
                }
                true
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Retries [verifyInternetThroughVpn] for up to [totalTimeoutMs] before giving up,
     * instead of judging a server on a single attempt. This matters because a dead
     * route often fails FAST (an immediate "network unreachable" from the OS) rather
     * than slowly timing out -- a single attempt can look like it failed almost
     * instantly even though the real WireGuard handshake genuinely just needed a
     * little longer to land. Returns true the moment any attempt succeeds.
     */
    suspend fun verifyInternetThroughVpnWithRetries(
        context: Context,
        totalTimeoutMs: Int = 10_000,
        perAttemptTimeoutMs: Int = 2_000,
        delayBetweenAttemptsMs: Long = 500
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (verifyInternetThroughVpn(context, perAttemptTimeoutMs)) return@withContext true
            delay(delayBetweenAttemptsMs)
        }
        false
    }
}
