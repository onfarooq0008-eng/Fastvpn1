package com.fastvpnn.app.data

import android.content.Context

/** Simple app-level toggles surfaced in SettingsActivity. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("fastvpn_settings", Context.MODE_PRIVATE)

    /** When on, connect to the last-used (or fastest) server automatically on app launch. */
    var autoConnectEnabled: Boolean
        get() = prefs.getBoolean("auto_connect", false)
        set(value) = prefs.edit().putBoolean("auto_connect", value).apply()

    var lastConnectedServerId: String?
        get() = prefs.getString("last_server_id", null)
        set(value) = prefs.edit().putString("last_server_id", value).apply()

    /** Country codes the user starred in the server list ("Favorites" tab). */
    var favoriteCountries: Set<String>
        get() = prefs.getStringSet("favorite_countries", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("favorite_countries", value).apply()

    /** "system", "light", or "dark". Changed by the sun/moon button; new installs start in dark. */
    var themeMode: String
        get() = prefs.getString("theme_mode", "dark") ?: "dark"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    /** Package names of apps that should bypass the VPN (split tunneling). */
    var excludedPackages: Set<String>
        get() = prefs.getStringSet("excluded_packages", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("excluded_packages", value).apply()

    /**
     * URL of your registration backend (see /backend in this project), e.g.
     * "https://api.yourdomain.com". The app fetches the server list and its
     * own per-device tunnel config from this API, and registration with a
     * VPS happens automatically -- no manual add-client.sh needed per user.
     *
     * Reads from a per-device override first (stored locally, for testing a
     * different backend on your own device). If that's blank, falls back to
     * BuildConfig.DEFAULT_BACKEND_API_URL -- the value baked in at build time
     * (app/build.gradle), which is what every real user of the published app
     * gets automatically with zero setup on their end.
     */
    var backendApiUrl: String
        get() {
            val override = prefs.getString("backend_api_url", "") ?: ""
            return override.ifBlank { com.fastvpnn.app.BuildConfig.DEFAULT_BACKEND_API_URL }
        }
        set(value) = prefs.edit().putString("backend_api_url", value.trim()).apply()

    /** True if this device is using the compiled-in default rather than a manual override. */
    fun isUsingDefaultBackend(): Boolean = (prefs.getString("backend_api_url", "") ?: "").isBlank()

    /**
     * DNS used inside the tunnel. "server" (default) keeps whatever the connected
     * server itself specifies (Server.dns / the backend's per-server default).
     * The rest override it: "google", "cloudflare", "adblock" (AdGuard DNS -- blocks
     * ads/trackers), or "custom" (see [customDns]). Applied on top of the server's
     * own value right before connecting -- see [resolveDns].
     */
    var dnsMode: String
        get() = prefs.getString("dns_mode", "server") ?: "server"
        set(value) = prefs.edit().putString("dns_mode", value).apply()

    /** User-entered DNS server(s) for dnsMode == "custom", e.g. "9.9.9.9" or "9.9.9.9, 149.112.112.112". */
    var customDns: String
        get() = prefs.getString("custom_dns", "") ?: ""
        set(value) = prefs.edit().putString("custom_dns", value.trim()).apply()

    /**
     * Set by SettingsActivity whenever [dnsMode] or [customDns] changes. A DNS
     * resolver is part of the WireGuard interface config, which Android can only
     * apply by re-establishing the tunnel -- there is no way to push a new DNS
     * server into an already-running VPN interface. Without this flag, changing
     * the setting while connected silently does nothing until the user happens
     * to disconnect and reconnect on their own, which looks exactly like "the
     * setting doesn't work". MainActivity.onResume() checks this and, if a
     * tunnel is currently up, transparently reconnects to apply it.
     */
    var dnsChangePendingReconnect: Boolean
        get() = prefs.getBoolean("dns_change_pending_reconnect", false)
        set(value) = prefs.edit().putBoolean("dns_change_pending_reconnect", value).apply()

    /** Resolves [dnsMode] against the connecting server's own default ([serverDns]).
     *  Falls back to [serverDns] for "server" mode, and also for "custom" mode if
     *  no custom value has been saved yet (so an empty custom field never breaks
     *  the connection -- it just behaves like "server default" until filled in). */
    fun resolveDns(serverDns: String): String = when (dnsMode) {
        "google" -> "8.8.8.8, 8.8.4.4"
        "cloudflare" -> "1.1.1.1, 1.0.0.1"
        "adblock" -> "94.140.14.14, 94.140.15.15" // AdGuard DNS Default -- ad/tracker blocking, no signup needed
        "custom" -> customDns.ifBlank { serverDns }
        else -> serverDns
    }

    /** Whether the user has accepted the Privacy Policy / Terms on the consent
     *  screen shown before first use. Gates access to MainActivity. */
    /** "Fastest Server" row selected in the server list: Connect picks a random reachable server. */
    var fastestServerMode: Boolean
        get() = prefs.getBoolean("fastest_server_mode", false)
        set(value) = prefs.edit().putBoolean("fastest_server_mode", value).apply()

    /** Ad privacy choice: "unset" (never asked), "personalized" or "declined". See ads/AdsConsent.kt. */
    var adConsent: String
        get() = prefs.getString("ad_consent", "unset") ?: "unset"
        set(value) = prefs.edit().putString("ad_consent", value).apply()

    /** The user agreed to see ads (required to use the app). New key, so everyone is asked again after this update. */
    var adsAccepted: Boolean
        get() = prefs.getBoolean("ads_accepted_v1", false)
        set(value) = prefs.edit().putBoolean("ads_accepted_v1", value).apply()

    var hasAcceptedTerms: Boolean
        get() = prefs.getBoolean("has_accepted_terms", false)
        set(value) = prefs.edit().putBoolean("has_accepted_terms", value).apply()

}
