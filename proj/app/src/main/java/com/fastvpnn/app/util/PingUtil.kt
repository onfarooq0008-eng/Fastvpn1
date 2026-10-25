package com.fastvpnn.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    // The blocking probe runs in its OWN scope, not as a child of the caller. A coroutine that is
    // stuck inside a blocking JVM call (DNS lookup, socket connect) cannot be interrupted, and a
    // parent scope always waits for its children -- so a probe started as a child would make
    // withTimeoutOrNull wait for it anyway and the timeout would never actually fire.
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun pingHost(host: String, port: Int, timeoutMs: Int = 1500): Int {
        if (host.isBlank() || host == "0.0.0.0") return -2
        val probe = probeScope.async { tcpConnectMs(host, port, timeoutMs) }
        // InetSocketAddress(host, port) resolves DNS synchronously with no timeout of its own, which
        // on a cold/flaky network can block far longer than the connect timeout. Waiting on the
        // probe here (a real suspension point) lets us give up promptly and report "unreachable"
        // instead of leaving every row stuck on "Checking..." until the lookup finally returns.
        return withTimeoutOrNull(timeoutMs * 2L) { probe.await() } ?: -2
    }

    private fun tcpConnectMs(host: String, port: Int, timeoutMs: Int): Int = try {
        val start = System.currentTimeMillis()
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        (System.currentTimeMillis() - start).toInt()
    } catch (e: Exception) {
        -2 // unreachable / timeout
    }

    /** Ping many hosts concurrently, returns map of id -> ms (-2 if unreachable). */
    suspend fun pingAll(targets: List<Triple<String, String, Int>>): Map<String, Int> = coroutineScope {
        targets.map { (id, host, port) -> async { id to pingHost(host, port) } }.awaitAll().toMap()
    }
}
