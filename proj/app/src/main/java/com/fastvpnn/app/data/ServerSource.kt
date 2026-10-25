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

    private val appContext = context.applicationContext

    /**
     * Returns the server list, using the on-disk cache so the app doesn't hit the network on every
     * open (see [ServerCache]):
     *  - cache younger than 24h and [forceRefresh] is false -> returned immediately, no network;
     *  - otherwise the backend is fetched and the result saved for next time;
     *  - if that fetch fails and an older cached list exists, the old list is returned instead of an
     *    error (unless [forceRefresh], where the caller wants to know it failed).
     *
     * We never turn a failure into an empty list: the caller keeps its last known-good list on a
     * thrown exception, and an empty list would look like "the backend really has zero servers".
     */
    suspend fun getServers(forceRefresh: Boolean = false): List<Server> {
        val backendUrl = appSettings.backendApiUrl
        val cached = ServerCache.snapshot(appContext)
        if (!forceRefresh && cached != null && cached.isFresh()) return cached.servers
        return try {
            val servers = backendClient.fetchServers(backendUrl)
            ServerCache.save(appContext, backendUrl, servers)
            servers
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!forceRefresh && cached != null) cached.servers else throw e
        }
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
