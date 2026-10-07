package com.fastvpnn.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real ICMP ping needs root on most Android devices, so instead we measure
 * TCP connect time -- but NOT to the server's WireGuard port. WireGuard is
 * UDP-only, and by design silently drops anything that isn't a valid
 * handshake (that's a deliberate anti-scanning security feature of the
 * protocol), so a TCP probe against it would basically always time out even
 * when the server is perfectly healthy. Callers pass port 22 (SSH) instead --
 * virtually always open on a VPS (you needed it to set the server up in the
 * first place), and a reasonable proxy for "is this host up and reachable."
 * It doesn't prove WireGuard itself is running, just that the host answers.
 */
object PingUtil {

    suspend fun pingHost(host: String, port: Int, timeoutMs: Int = 1500): Int = withContext(Dispatchers.IO) {
        if (host.isBlank() || host == "0.0.0.0") return@withContext -2
        // withTimeoutOrNull bounds the WHOLE attempt, not just the TCP connect.
        // InetSocketAddress(host, port) resolves DNS synchronously with no
        // timeout of its own -- on a cold/flaky network (very common right
        // after a fresh install, before the OS resolver has anything cached)
        // that lookup alone can block far longer than timeoutMs. Without this
        // outer bound, one slow-to-resolve host hangs the whole pingAll()
        // batch (they're awaited together), leaving every row on the home
        // screen stuck on "Checking..." until the app is restarted onto a
        // since-stabilized network -- restarting isn't what actually fixes
        // it, waiting is. Cooperative cancellation can't interrupt the
        // blocking JVM call underneath, but it does let this coroutine give
        // up and report "unreachable" promptly instead of waiting on it.
        withTimeoutOrNull(timeoutMs.toLong()) {
            try {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
                (System.currentTimeMillis() - start).toInt()
            } catch (e: Exception) {
                -2 // unreachable / timeout
            }
        } ?: -2 // outer timeout fired -- DNS or connect took too long
    }

    /** Ping many hosts concurrently, returns map of id -> ms (-2 if unreachable). */
    suspend fun pingAll(targets: List<Triple<String, String, Int>>): Map<String, Int> = coroutineScopePingAll(targets)

    private suspend fun coroutineScopePingAll(targets: List<Triple<String, String, Int>>): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val jobs = targets.map { (id, host, port) ->
                async { id to pingHost(host, port) }
            }
            jobs.awaitAll().toMap()
        }
}
