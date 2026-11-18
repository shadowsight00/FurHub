package com.unifiedart.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val USER_AGENT = "FurHub/0.5 (Android; https://github.com/shadowsight00/FurHub)"

class ApiException(val code: Int, message: String) : IOException(message)

/** Per-source serialization and conservative spacing, shared by search and detail calls. */
class ApiClient(private val spacingMillis: Long) {
    private val mutex = Mutex()
    private var lastStart = 0L
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()

    suspend fun json(url: String, form: Map<String, String>? = null,
        headers: Map<String, String> = emptyMap(), method: String = if (form == null) "GET" else "POST"): JSONObject = mutex.withLock {
        var failure: IOException? = null
        for (attempt in 0..2) {
            delay((spacingMillis - (System.nanoTime() / 1_000_000 - lastStart)).coerceAtLeast(0))
            lastStart = System.nanoTime() / 1_000_000
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).apply {
                headers.forEach { (key, value) -> header(key, value) }
                if (method == "DELETE") delete()
                else if (form != null) post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
            }.build()
            var retryAfter = 0L
            try {
                val response = client.newCall(request).await()
                val result = withContext(Dispatchers.IO) {
                    response.use {
                        if (!it.isSuccessful) {
                            retryAfter = it.header("Retry-After")?.toLongOrNull()?.times(1000) ?: 0
                            throw ApiException(it.code, "Request failed (HTTP ${it.code})")
                        }
                        val text = it.body?.string() ?: throw IOException("Empty response")
                        if (text.trimStart().startsWith("[")) JSONObject().put("items", JSONArray(text)) else JSONObject(text)
                    }
                }
                return@withLock result
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: IOException) {
                failure = error
                if (error is ApiException && error.code != 429 && error.code !in 500..599) throw error
                if (attempt == 2) throw error
                // Keep retry waits bounded; a user can cancel the request at any point.
                delay(maxOf(1000L shl attempt, retryAfter).coerceAtMost(60_000))
            }
        }
        throw failure ?: IOException("Request failed")
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

object ApiClients { val e621 = ApiClient(650); val inkbunny = ApiClient(1100) }
