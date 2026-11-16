package com.unifiedart.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

interface ArtSource {
    val source: Source
    suspend fun search(query: SearchQuery, page: Int, resultId: String? = null): Page
    suspend fun details(art: Artwork): Artwork
}

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() && it != "null" }
internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.https(key: String): String = optString(key).takeIf { it.startsWith("https://") } ?: ""
internal fun JSONObject.truth(key: String): Boolean = optString(key).lowercase() in setOf("true", "t", "1", "yes")

fun timestamp(value: String): Long {
    val normalized = value.trim().replaceFirst(' ', 'T')
        .replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        .replace(Regex("([+-]\\d{2})$"), "$1:00")
    return try { OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toEpochSecond() }
    catch (_: Exception) { 0L }
}

class E621Source(private val api: ApiClient = ApiClient(650)) : ArtSource {
    override val source = Source.E621
    override suspend fun search(query: SearchQuery, page: Int, resultId: String?): Page {
        val url = "https://e621.net/posts.json".toHttpUrl().newBuilder()
            .addQueryParameter("tags", query.e621).addQueryParameter("limit", "40")
            .addQueryParameter("page", page.toString()).build()
        val response = api.json(url.toString())
        val posts = response.optJSONArray("posts") ?: throw IOException("e621 returned an unexpected response")
        return Page(posts.objects().map(::parse), posts.length() >= 40)
    }
    override suspend fun details(art: Artwork): Artwork = art

    internal fun parse(post: JSONObject): Artwork {
        val file = post.optJSONObject("file") ?: JSONObject()
        val tags = post.optJSONObject("tags") ?: JSONObject()
        val id = post.get("id").toString()
        return Artwork(source, id, "Post #$id", tags.optJSONArray("artist").strings(),
            tags.keys().asSequence().flatMap { tags.optJSONArray(it).strings() }.toList(),
            timestamp(post.optString("created_at")), post.optJSONObject("preview")?.https("url") ?: "",
            file.https("url").takeIf { it.isNotEmpty() }?.let {
                listOf(MediaFile(it, file.optString("ext"), file.optInt("width"), file.optInt("height")))
            } ?: emptyList(), favorite = post.optBoolean("is_favorited"), pools = post.optJSONArray("pools").strings())
    }
}

class InkbunnySource(private val api: ApiClient = ApiClient(1100)) : ArtSource {
    override val source = Source.INKBUNNY
    private var sid: String? = null
    private val sessionLock = Mutex()

    private suspend fun session(): String = sessionLock.withLock {
        sid ?: run {
            val result = api.json("https://inkbunny.net/api_login.php", mapOf("username" to "guest"))
            checkError(result)
            val newSid = result.optString("sid").takeIf { it.isNotBlank() && it != "null" }
                ?: throw IOException("Inkbunny did not create a guest session")
            // Official guest-session controls. Never override registered account preferences.
            val ratings = api.json("https://inkbunny.net/api_userrating.php", mapOf(
                "sid" to newSid, "tag[2]" to "yes", "tag[3]" to "yes", "tag[4]" to "yes", "tag[5]" to "yes"))
            checkError(ratings)
            sid = newSid
            newSid
        }
    }

    private fun checkError(result: JSONObject) {
        if (result.has("error_code")) throw InkbunnyError(result.optInt("error_code", -1))
    }
    private suspend fun request(endpoint: String, params: Map<String, String>): JSONObject {
        repeat(2) { attempt ->
            val sessionId = session()
            val result = api.json("https://inkbunny.net/$endpoint", params + ("sid" to sessionId))
            if (result.has("error_code") && result.optInt("error_code") == 2 && attempt == 0) {
                sessionLock.withLock { if (sid == sessionId) sid = null }
            } else { checkError(result); return result }
        }
        throw IOException("Inkbunny session expired; refresh to try again")
    }

    override suspend fun search(query: SearchQuery, page: Int, resultId: String?): Page {
        val params = mutableMapOf("page" to page.toString(), "submissions_per_page" to "40")
        if (resultId != null) params["rid"] = resultId
        else {
            params += mapOf("text" to query.inkbunny, "orderby" to "create_datetime", "get_rid" to "yes", "keywords" to "yes")
            if (query.inkArtist.isNotBlank()) params["username"] = query.inkArtist
        }
        val result = request("api_search.php", params)
        val posts = result.optJSONArray("submissions") ?: throw IOException("Inkbunny returned an unexpected response")
        return Page(posts.objects().map(::parse), page < result.optInt("pages_count", page),
            result.optString("rid").takeIf { it.isNotBlank() && it != "null" } ?: resultId)
    }

    override suspend fun details(art: Artwork): Artwork {
        val result = request("api_submissions.php", mapOf("submission_ids" to art.id, "show_pools" to "yes"))
        val post = result.optJSONArray("submissions")?.optJSONObject(0) ?: throw IOException("Submission is unavailable to this session")
        return parse(post)
    }

    internal fun parse(post: JSONObject): Artwork {
        val id = post.get("submission_id").toString()
        val files = post.optJSONArray("files").objects().sortedBy { it.optInt("submission_file_order") }.mapNotNull { file ->
            file.https("file_url_full").takeIf { it.isNotEmpty() }?.let {
                MediaFile(it, file.optString("mimetype"), file.optInt("full_size_x"), file.optInt("full_size_y"))
            }
        }
        val direct = post.https("file_url_full").takeIf { it.isNotEmpty() }?.let {
            listOf(MediaFile(it, post.optString("mimetype")))
        } ?: emptyList()
        return Artwork(source, id, post.optString("title", "Submission #$id"),
            listOfNotNull(post.optString("username").takeIf { it.isNotBlank() }),
            post.optJSONArray("keywords").objects().map { it.optString("keyword_name") },
            timestamp(post.optString("create_datetime")),
            sequenceOf("thumbnail_url_large", "thumbnail_url_medium", "thumbnail_url_huge").map { post.https(it) }.firstOrNull { it.isNotEmpty() } ?: "",
            files.ifEmpty { direct }, post.optInt("pagecount", files.size.coerceAtLeast(1)),
            post.truth("favorite"), post.optJSONArray("pools").objects().map { it.optString("pool_id") }, post.truth("hidden"))
    }
}

private class InkbunnyError(code: Int) : IOException("Inkbunny API error $code. Refresh the search to retry.")
