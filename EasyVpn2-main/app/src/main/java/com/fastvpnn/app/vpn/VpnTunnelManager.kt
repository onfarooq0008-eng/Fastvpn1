package com.fastvpnn.app.vpn

import android.content.Context
import android.util.Log
import com.fastvpnn.app.data.Server
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class TunnelState { DOWN, CONNECTING, UP }

/**
 * Thin wrapper around the official com.wireguard.android GoBackend.
 * The client's own private key is generated once on-device and never leaves it.
 * In production/backend mode the control API allocates the client tunnel IP
 * atomically and returns it with the server configuration.
 *
 * ============================================================================
 * TUNNEL LIFECYCLE OWNERSHIP -- read before touching connect()/disconnect()/
 * syncStateFromBackend(). Verified against the actual wireguard-android
 * source (GoBackend.java), not just this library's public API docs:
 *
 * - GoBackend keeps `currentTunnel`/`currentConfig` as PRIVATE, PER-INSTANCE
 *   fields. A second `GoBackend(context)` object has no way to see those --
 *   which is exactly why VpnTunnelManagerHolder exists: every component in
 *   this process (MainActivity, VpnActionReceiver) MUST share the one
 *   GoBackend instance held here, never construct their own.
 * - Separately, the real OS-level tunnel handle is bound via a Java
 *   `static CompletableFuture<VpnService>` field on GoBackend -- static, so
 *   it's shared PROCESS-WIDE regardless of which GoBackend instance asks.
 *   This is why `getRunningTunnelNames()` is trustworthy even from a fresh
 *   GoBackend instance within the same process: it queries the real,
 *   process-shared VpnService/native state, not the per-instance fields.
 * - Reattaching via `SimpleTunnel(TUNNEL_NAME)` after Activity recreation is
 *   therefore correct BECAUSE tunnels are identified by name at the native
 *   layer, not by Tunnel object identity -- as long as it's process-shared
 *   state being queried (getRunningTunnelNames), not per-instance state.
 *
 * - What this does NOT cover, and what genuinely still needs a real device:
 *   1. Full process death (not just Activity death). If the OS kills the
 *      whole process, the in-process wireguard-go native runtime dies with
 *      it -- there's no separate daemon. getRunningTunnelNames() on restart
 *      should then correctly report nothing running. But whether Android
 *      restarts the VpnService automatically first (via its own retry/
 *      always-on logic) before our code ever runs is OS/OEM-dependent and
 *      cannot be confirmed from source reading alone.
 *   2. The wireguard-android maintainers themselves have had to patch a
 *      "VPN service expiration" bug where Android destroys the VpnService
 *      after a timeout independent of anything this app does -- meaning
 *      the tunnel can go down for reasons outside connect()/disconnect()
 *      entirely. syncStateFromBackend() is the mitigation (call it whenever
 *      the UI needs a trustworthy state), but its actual reliability across
 *      OEM battery-management skins (MIUI, ColorOS, etc.) is not something
 *      that can be verified without physical devices running those skins.
 *
 * NOT YET VERIFIED ON A REAL DEVICE. See MANUAL_TESTING.md for the exact
 * checklist to run before relying on this for a public release.
 * ============================================================================
 */
class VpnTunnelManager(private val context: Context) {

    companion object {
        const val TUNNEL_NAME = "fastvpn"
        private const val TAG = "FastVPN-Tunnel"
    }

    private val backend = GoBackend(context)
    private var currentTunnel: SimpleTunnel? = null
    private val operationMutex = Mutex()

    var state: TunnelState = TunnelState.DOWN
        private set

    private class SimpleTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) { /* observed via manager */ }
    }

    /** Call once and persist the returned key pair (store private key securely, e.g. EncryptedSharedPreferences). */
    fun generateKeyPair(): com.wireguard.crypto.KeyPair = com.wireguard.crypto.KeyPair()

    suspend fun connect(
        server: Server,
        clientPrivateKeyBase64: String,
        excludedPackages: Set<String> = emptySet(),
        assignedAddressCidr: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
        try {
            state = TunnelState.CONNECTING
            val peerPublicKey = com.wireguard.crypto.Key.fromBase64(server.serverPublicKey)

            // The tunnel address MUST come from the server-side allocator.
            // Client-side/hash-derived allocation was removed because it can collide
            // between different devices and is not authoritative for WireGuard peers.
            require(assignedAddressCidr.isNotBlank()) {
                "A server-assigned tunnel address is required"
            }

            val ifaceBuilder = Interface.Builder()
                .parsePrivateKey(clientPrivateKeyBase64)
                .parseAddresses(assignedAddressCidr)
                .parseDnsServers(server.dns)

            if (excludedPackages.isNotEmpty()) {
                ifaceBuilder.excludeApplications(excludedPackages)
            }

            val peerBuilder = Peer.Builder()
                .setPublicKey(peerPublicKey)
                // Routing BOTH families through the tunnel is what actually prevents an
                // IPv6 leak. Android's VpnService only captures traffic for address
                // families it's given a route for -- "0.0.0.0/0" alone only claims IPv4,
                // so on any dual-stack network (most mobile/Wi-Fi networks today) IPv6
                // traffic would silently bypass the tunnel entirely and go out over the
                // real interface with the device's real IPv6 address, unencrypted. Since
                // this WireGuard server/config here doesn't hand out an IPv6 address,
                // adding the "::/0" route just makes IPv6 traffic route into the tunnel
                // and get dropped there instead of leaking -- a safe fail-closed result.
                .parseAllowedIPs("0.0.0.0/0, ::/0")
                .parseEndpoint(server.endpoint)
                .setPersistentKeepalive(25)

            if (server.presharedKey.isNotBlank()) {
                peerBuilder.parsePreSharedKey(server.presharedKey)
            }

            val config = Config.Builder()
                .setInterface(ifaceBuilder.build())
                .addPeer(peerBuilder.build())
                .build()

            val tunnel = SimpleTunnel(TUNNEL_NAME)
            backend.setState(tunnel, Tunnel.State.UP, config)
            currentTunnel = tunnel
            state = TunnelState.UP
            Log.d(TAG, "connect: tunnel up on ${server.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            state = TunnelState.DOWN
            Log.e(TAG, "connect: failed", e)
            Result.failure(e)
        }
        }
    }

    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
        try {
            currentTunnel?.let { backend.setState(it, Tunnel.State.DOWN, null) }
            currentTunnel = null
            state = TunnelState.DOWN
            Log.d(TAG, "disconnect: tunnel torn down")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "disconnect: failed -- tunnel may still be UP", e)
            Result.failure(e)
        }
        }
    }

    /**
     * Synchronize our in-memory state with this manager's GoBackend.
     *
     * getRunningTunnelNames() queries process-wide, static-field-backed state
     * on GoBackend (see the class doc above) -- it is authoritative for "is a
     * real tunnel by this name up right now" independent of whether THIS
     * GoBackend instance's own currentTunnel field happens to be set. We must
     * never manufacture a new SimpleTunnel merely because Android's generic
     * VPN UI reports some interface active (that could be a different app's
     * VPN); we only do it once this specific tunnel name is confirmed running.
     *
     * VpnTunnelManagerHolder guarantees that all app components in the same
     * process use this manager/backend instance. If the process itself was
     * killed, the in-process wireguard-go native runtime dies with it -- the
     * correct state for a freshly-restarted process is DOWN, and this method
     * should report that correctly since there is no real tunnel left to find.
     */
    fun syncStateFromBackend(): TunnelState {
        return try {
            val running = backend.getRunningTunnelNames().contains(TUNNEL_NAME)
            state = if (running) {
                if (currentTunnel == null) {
                    Log.d(TAG, "syncStateFromBackend: reattaching to running tunnel '$TUNNEL_NAME'")
                    currentTunnel = SimpleTunnel(TUNNEL_NAME)
                }
                TunnelState.UP
            } else {
                if (currentTunnel != null) Log.d(TAG, "syncStateFromBackend: tunnel no longer running, clearing handle")
                currentTunnel = null
                TunnelState.DOWN
            }
            state
        } catch (e: Exception) {
            Log.e(TAG, "syncStateFromBackend: getRunningTunnelNames() threw", e)
            state = if (currentTunnel != null) TunnelState.UP else TunnelState.DOWN
            state
        }
    }

    fun statistics() = currentTunnel?.let { runCatching { backend.getStatistics(it) }.getOrNull() }
}
