package com.fastvpnn.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Result of registering this device with a server via the control API. */
data class BackendRegistration(
    val serverId: String,
    val endpointHost: String,
    val endpointPort: Int,
    val serverPublicKey: String,
    val dns: String,
    val assignedAddress: String,
    val created: Boolean,
    val registrationToken: String
)

/**
 * Talks to your /backend control API (see that folder for the server-side
 * code). AppSettings.backendApiUrl is always set in a real build (it falls
 * back to the compiled-in BuildConfig.DEFAULT_BACKEND_API_URL), so this is
 * the app's only path for fetching servers and registering devices.
 */
class BackendApiClient {

    private val client = OkHttpClient.Builder()
        // Kept short on purpose: the app now re-polls /api/servers every 20s
        // (see MainActivity.SERVER_REFRESH_INTERVAL_MS), so a slow/unreachable
        // backend should fail fast and let the next tick try again, rather than
        // hanging most of that 20s window on one stuck request.
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun fetchServers(baseUrl: String): List<Server> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/servers").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw java.io.IOException("Empty response")
            val array = JSONArray(body)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Server(
                    id = o.getString("id"),
                    name = o.optString("name", o.optString("countryName")),
                    countryName = o.optString("countryName"),
                    countryCode = o.optString("countryCode", "US"),
                    city = o.optString("city"),
                    endpointHost = o.getString("endpointHost"),
                    endpointPort = o.optInt("endpointPort", 51820),
                    serverPublicKey = o.getString("serverPublicKey"),
                    dns = o.optString("dns", "1.1.1.1"),
                    streaming = o.optBoolean("streaming", false)
                )
            }
        }
    }

    suspend fun register(baseUrl: String, devicePublicKeyBase64: String, preferredServerId: String?): BackendRegistration =
        withContext(Dispatchers.IO) {
            val json = JSONObject().apply {
                put("devicePublicKey", devicePublicKeyBase64)
                if (preferredServerId != null) put("preferredServerId", preferredServerId)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/register")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: throw java.io.IOException("Empty response")
                if (!response.isSuccessful) {
                    val err = runCatching { JSONObject(responseBody).optString("error") }.getOrNull()
                    throw java.io.IOException(err ?: "HTTP ${response.code}")
                }
                val o = JSONObject(responseBody)
                BackendRegistration(
                    serverId = o.getString("serverId"),
                    endpointHost = o.getString("endpointHost"),
                    endpointPort = o.optInt("endpointPort", 51820),
                    serverPublicKey = o.getString("serverPublicKey"),
                    dns = o.optString("dns", "1.1.1.1"),
                    assignedAddress = o.getString("assignedAddress"),
                    created = o.optBoolean("created", false),
                    registrationToken = o.optString("registrationToken", "")
                )
            }
        }
    suspend fun unregister(baseUrl: String, devicePublicKeyBase64: String, serverId: String, registrationToken: String) =
        withContext(Dispatchers.IO) {
            val json = JSONObject().apply {
                put("devicePublicKey", devicePublicKeyBase64)
                put("serverId", serverId)
                put("registrationToken", registrationToken)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/unregister")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = runCatching { JSONObject(responseBody).optString("error") }.getOrNull()
                    throw java.io.IOException(err ?: "HTTP ${response.code}")
                }
            }
        }

}
