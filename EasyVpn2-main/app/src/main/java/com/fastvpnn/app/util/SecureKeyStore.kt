package com.fastvpnn.app.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wireguard.crypto.KeyPair
import org.json.JSONArray
import org.json.JSONObject

/** Generates the device's WireGuard key pair once, stores the private key encrypted-at-rest.
 *
 *  Uses EncryptedSharedPreferences/MasterKey, which security-crypto 1.1.0 deprecates in
 *  favor of Jetpack DataStore + Tink. Left as-is here since the deprecated APIs are still
 *  fully functional and migrating would require a data migration for already-stored keys
 *  (so existing installs don't lose their WireGuard identity) -- worth planning as its own
 *  change rather than folding it into an unrelated dependency bump. */
class SecureKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context, "fastvpn_secure", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getOrCreateKeyPair(): KeyPair {
        val existing = prefs.getString("priv_key", null)
        if (existing != null) return KeyPair(com.wireguard.crypto.Key.fromBase64(existing))
        val pair = KeyPair()
        prefs.edit().putString("priv_key", pair.privateKey.toBase64()).apply()
        return pair
    }

    /** Give this public key to your server-setup script to register the client as a peer. */

    data class RegistrationLease(val serverId: String, val token: String)

    @Synchronized
    fun addPendingRegistration(serverId: String, token: String) {
        if (serverId.isBlank() || token.isBlank()) return
        val leases = pendingRegistrations().filterNot { it.serverId == serverId && it.token == token }.toMutableList()
        if (leases.none { it.serverId == serverId }) leases.add(RegistrationLease(serverId, token))
        val array = JSONArray()
        leases.forEach { array.put(JSONObject().put("serverId", it.serverId).put("token", it.token)) }
        prefs.edit().putString("pending_registrations", array.toString()).apply()
    }

    @Synchronized
    fun pendingRegistrations(): List<RegistrationLease> {
        val raw = prefs.getString("pending_registrations", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val serverId = o.optString("serverId")
                    val token = o.optString("token")
                    if (serverId.isNotBlank() && token.isNotBlank()) add(RegistrationLease(serverId, token))
                }
            }
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun removePendingRegistration(serverId: String, token: String) {
        val remaining = pendingRegistrations().filterNot { it.serverId == serverId && it.token == token }
        val array = JSONArray()
        remaining.forEach { array.put(JSONObject().put("serverId", it.serverId).put("token", it.token)) }
        prefs.edit().putString("pending_registrations", array.toString()).apply()
    }

    @Synchronized
    fun setActiveRegistration(serverId: String, token: String) {
        prefs.edit().putString("active_registration_server_id", serverId)
            .putString("active_registration_token", token).apply()
    }

    fun activeRegistration(): RegistrationLease? {
        val serverId = prefs.getString("active_registration_server_id", null) ?: return null
        val token = prefs.getString("active_registration_token", null) ?: return null
        if (serverId.isBlank() || token.isBlank()) return null
        return RegistrationLease(serverId, token)
    }

    @Synchronized
    fun clearActiveRegistration() {
        prefs.edit().remove("active_registration_server_id").remove("active_registration_token").apply()
    }

    @Synchronized
    fun promotePendingToActive(serverId: String, token: String) {
        removePendingRegistration(serverId, token)
        setActiveRegistration(serverId, token)
    }
    fun clientPublicKeyBase64(): String = getOrCreateKeyPair().publicKey.toBase64()
    fun clientPrivateKeyBase64(): String = getOrCreateKeyPair().privateKey.toBase64()
}
