package com.fastvpnn.app.vpn

import android.content.Context

/**
 * Process-wide owner of the VpnTunnelManager/GoBackend pair.
 *
 * GoBackend keeps the active WireGuard Tunnel and native handle as private
 * instance state. Every component that can control the VPN therefore must use
 * this same manager instance. In particular, the notification receiver must
 * not create a second GoBackend.
 */
object VpnTunnelManagerHolder {
    @Volatile private var instance: VpnTunnelManager? = null

    fun get(context: Context): VpnTunnelManager {
        return instance ?: synchronized(this) {
            instance ?: VpnTunnelManager(context.applicationContext).also { instance = it }
        }
    }
}
