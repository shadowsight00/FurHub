package com.unifiedart.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AccountState(val e621Name: String = "", val inkbunnyName: String = "", val message: String = "")

/** Only API keys and session tokens are retained. Passwords are never persisted. */
class Accounts private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("secure_accounts", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val eApi = ApiClients.e621
    private val iApi = ApiClients.inkbunny
    @Volatile private var data = JSONObject()
    private val mutable = MutableStateFlow(AccountState())
    val state = mutable.asStateFlow()
    init {
        try {
            prefs.getString("vault", null)?.let { encoded ->
                val parts = encoded.split(':', limit = 2)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                data = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            }
            publish()
        } catch (_: Exception) { mutable.value = AccountState(message = "Account storage couldn't be opened. Reconnect your accounts.") }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("unifiedart.accounts", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("unifiedart.accounts", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun save() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("vault", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "Couldn't save account connection" }
        publish()
    }
    private fun publish() { mutable.value = AccountState(data.optString("eName"), data.optString("iName")) }
    fun eHeaders(): Map<String, String> {
        val snapshot = data
        return snapshot.optString("eKey").takeIf { it.isNotEmpty() }?.let {
            mapOf("Authorization" to Credentials.basic(snapshot.optString("eName"), it))
        } ?: emptyMap()
    }
    val eId get() = data.optString("eId")
    val eBlacklist get() = data.optString("eBlacklist")
    val inkSid get() = data.optString("iSid")
    val inkId get() = data.optString("iId")
    val inkRatings get() = data.optString("iRatings")
    suspend fun connectE621(name: String, apiKey: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val result = eApi.json("https://e621.net/users/" + name.trim().toHttpSegment() + ".json",
                headers = mapOf("Authorization" to Credentials.basic(name.trim(), apiKey.trim())))
            if (!result.has("blacklisted_tags") || !result.optString("name").equals(name.trim(), ignoreCase = true))
                throw IOException("e621 couldn't verify this account/API key")
            data = JSONObject(data.toString()).put("eName", result.getString("name")).put("eKey", apiKey.trim()).put("eId", result.get("id").toString())
                .put("eBlacklist", result.optString("blacklisted_tags"))
            save()
        }
    }
    suspend fun refreshE621() = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(eId.isNotBlank()) { "Connect e621 first" }
            val result = eApi.json("https://e621.net/users/$eId.json", headers = eHeaders())
            if (!result.has("blacklisted_tags")) throw IOException("e621 connection expired; reconnect")
            data = JSONObject(data.toString()).put("eBlacklist", result.optString("blacklisted_tags")); save()
        }
    }
    suspend fun connectInkbunny(name: String, password: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val result = iApi.json("https://inkbunny.net/api_login.php", mapOf("username" to name.trim(), "password" to password))
            if (result.has("error_code")) throw IOException("Inkbunny login failed. Check credentials and Enable API Access in account settings.")
            val sid = result.optString("sid").takeIf { it.isNotBlank() && it != "null" } ?: throw IOException("No Inkbunny session returned")
            data = JSONObject(data.toString()).put("iName", name.trim()).put("iSid", sid).put("iId", result.get("user_id").toString())
                .put("iRatings", result.optString("ratingsmask"))
            save()
        }
    }
    suspend fun logout(source: Source) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (source == Source.INKBUNNY && inkSid.isNotEmpty()) {
                // Local credentials are removed even if the remote session cannot be revoked.
                try { iApi.json("https://inkbunny.net/api_logout.php", mapOf("sid" to inkSid)) } catch (_: IOException) { }
            }
            val keys = if (source == Source.E621) listOf("eName", "eKey", "eId", "eBlacklist") else listOf("iName", "iSid", "iId", "iRatings")
            data = JSONObject(data.toString()).apply { keys.forEach { remove(it) } }; save()
        }
    }
    fun mediaUrl(raw: String): String {
        val url = runCatching { raw.toHttpUrl() }.getOrNull() ?: return raw
        return if (url.host == "inkbunny.net" && (url.encodedPath.startsWith("/private_files/") || url.encodedPath.startsWith("/private_thumbnails/")) && inkSid.isNotBlank())
            url.newBuilder().setQueryParameter("sid", inkSid).build().toString() else raw
    }
    companion object {
        @Volatile private var instance: Accounts? = null
        fun get(context: Context): Accounts = instance ?: synchronized(this) {
            instance ?: Accounts(context.applicationContext).also { instance = it }
        }
    }
}
private fun String.toHttpSegment() = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
