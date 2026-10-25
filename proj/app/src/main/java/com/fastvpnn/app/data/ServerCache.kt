package com.fastvpnn.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent server-list cache + one-shot start-up prefetch.
 *
 * How it makes the app feel instant:
 *  1. Every successful fetch is written to disk ([save]) together with a timestamp.
 *  2. On the next launch [snapshot] hands MainActivity that saved list immediately (a few KB of
 *     JSON from local storage -- no network), so the Locations list is on screen before any
 *     request has even been sent.
 *  3. The list is considered fresh for [TTL_MS] (24 hours). While fresh, ServerSource serves it
 *     straight from the cache and never touches the network. Once it is older than that, the
 *     cached list is still shown instantly and a refresh runs quietly in the background
 *     ("stale-while-revalidate"); if that refresh fails the old list simply stays.
 *  4. Pull-to-refresh always bypasses the cache.
 *
 * [warm] is called by SplashActivity so that, when a refresh IS due, the request is already in
 * flight while the logo shows. It runs on a process-level scope (NOT the Activity's
 * lifecycleScope -- Splash finishes right away, which would cancel the request).
 */
object ServerCache {
    /** How long a saved server list is trusted before a background refresh is due. */
    const val TTL_MS = 24L * 60 * 60 * 1000

    private const val FILE_NAME = "servers_cache_v1.json"
    private const val VERSION = 1

    /** A saved server list. [backendUrl] ties it to the backend it came from. */
    class Snapshot(val servers: List<Server>, val savedAtMs: Long, val backendUrl: String) {
        /** A clock set backwards makes the age negative -- treat that as stale, never "fresh forever". */
        fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean {
            val age = nowMs - savedAtMs
            return age in 0 until TTL_MS
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var memory: Snapshot? = null

    @Volatile
    private var prefetch: Deferred<List<Server>>? = null

    /** The saved list for the CURRENT backend, or null if there is none yet. Memory first, then disk.
     *  Returns fresh Server copies each call (ping times are mutated in place by the UI). */
    fun snapshot(context: Context): Snapshot? {
        val appContext = context.applicationContext
        val backendUrl = AppSettings(appContext).backendApiUrl
        val snap = synchronized(lock) {
            memory ?: readFromDisk(appContext)?.also { memory = it }
        } ?: return null
        // A per-device backend override was changed: that list belongs to a different backend.
        if (snap.backendUrl != backendUrl) return null
        return Snapshot(snap.servers.map { it.copy() }, snap.savedAtMs, snap.backendUrl)
    }

    /** When the saved list was fetched, without copying the list (cheap enough to call on every refresh). */
    fun savedAtMs(context: Context): Long? {
        val appContext = context.applicationContext
        return synchronized(lock) {
            memory ?: readFromDisk(appContext)?.also { memory = it }
        }?.takeIf { it.backendUrl == AppSettings(appContext).backendApiUrl }?.savedAtMs
    }

    /** Stores a freshly fetched list (memory + disk). Never throws: a failed write only costs the cache. */
    fun save(context: Context, backendUrl: String, servers: List<Server>) {
        if (servers.isEmpty()) return // never replace a good list with "no servers" from a hiccup
        val snap = Snapshot(servers.map { it.copy() }, System.currentTimeMillis(), backendUrl)
        synchronized(lock) {
            memory = snap
            runCatching { writeToDisk(context.applicationContext, snap) }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            memory = null
            runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
        }
        prefetch = null
    }

    /** Starts the start-up fetch now. If a fresh cache exists this returns instantly with no
     *  network call (ServerSource serves it from disk); if it is stale or missing the request
     *  begins right away while the logo is still on screen. */
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

    // ---- disk format -------------------------------------------------------------------------

    private fun writeToDisk(context: Context, snap: Snapshot) {
        val array = JSONArray()
        snap.servers.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("countryName", s.countryName)
                    .put("countryCode", s.countryCode)
                    .put("city", s.city)
                    .put("endpointHost", s.endpointHost)
                    .put("endpointPort", s.endpointPort)
                    .put("serverPublicKey", s.serverPublicKey)
                    .put("dns", s.dns)
                    .put("enabled", s.enabled)
                    .put("streaming", s.streaming)
            )
        }
        val root = JSONObject()
            .put("version", VERSION)
            .put("savedAt", snap.savedAtMs)
            .put("backendUrl", snap.backendUrl)
            .put("servers", array)
        // Write to a temp file and rename, so a crash mid-write can never leave a half-written cache.
        val target = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun readFromDisk(context: Context): Snapshot? = try {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            null
        } else {
            val root = JSONObject(file.readText())
            if (root.optInt("version") != VERSION) {
                null
            } else {
                val array = root.getJSONArray("servers")
                val servers = (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    Server(
                        id = o.getString("id"),
                        name = o.optString("name"),
                        countryName = o.optString("countryName"),
                        countryCode = o.optString("countryCode", "US"),
                        city = o.optString("city"),
                        endpointHost = o.getString("endpointHost"),
                        endpointPort = o.optInt("endpointPort", 51820),
                        serverPublicKey = o.getString("serverPublicKey"),
                        dns = o.optString("dns", "1.1.1.1"),
                        enabled = o.optBoolean("enabled", true),
                        streaming = o.optBoolean("streaming", false)
                    )
                }
                if (servers.isEmpty()) null else Snapshot(servers, root.getLong("savedAt"), root.optString("backendUrl"))
            }
        }
    } catch (_: Exception) {
        null // corrupt / unreadable cache = just behave as if there is none
    }
}
