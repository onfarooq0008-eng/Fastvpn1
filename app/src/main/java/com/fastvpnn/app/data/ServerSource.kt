package com.fastvpnn.app.data

import android.content.Context

/**
 * Single place that talks to the backend control API (either the compiled-in
 * default from app/build.gradle, or a per-device override). Everything else
 * (home screen, country screen) just calls getServers() and doesn't need to
 * know the URL.
 */
class ServerSource(context: Context) {
    private val appSettings = AppSettings(context)
    private val backendClient = BackendApiClient()

    suspend fun getServers(): List<Server> {
        val backendUrl = appSettings.backendApiUrl
        // Backend is authoritative, so we don't silently fall back to a local list
        // (those servers couldn't be registered through the backend anyway) --
        // but we also must NOT swallow the failure into an empty list here. The
        // caller (MainActivity) keeps showing its last known-good server list on
        // a thrown exception; returning emptyList() on every hiccup used to look
        // identical to "the backend really has zero servers" and wiped the whole
        // list on screen for a purely transient network error.
        return backendClient.fetchServers(backendUrl)
    }

    suspend fun register(devicePublicKeyBase64: String, preferredServerId: String?): BackendRegistration {
        val backendUrl = appSettings.backendApiUrl
        return backendClient.register(backendUrl, devicePublicKeyBase64, preferredServerId)
    }
    suspend fun unregister(devicePublicKeyBase64: String, serverId: String, registrationToken: String) {
        val backendUrl = appSettings.backendApiUrl
        backendClient.unregister(backendUrl, devicePublicKeyBase64, serverId, registrationToken)
    }

}
