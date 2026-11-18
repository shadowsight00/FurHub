package com.unifiedart.app.data

enum class Source(val label: String) { E621("e621"), INKBUNNY("Inkbunny") }

data class MediaFile(
    val url: String, val type: String, val width: Int = 0, val height: Int = 0
) {
    val video get() = type.contains("video") || type.lowercase() in setOf("webm", "mp4", "m4v")
    val image get() = type.startsWith("image/") || type.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "avif")
    val label get() = type.substringAfter('/').uppercase()
}

data class Artwork(
    val source: Source, val id: String, val title: String, val artists: List<String>,
    val tags: List<String>, val uploadedAt: Long, val thumbnail: String,
    val files: List<MediaFile> = emptyList(), val pageCount: Int = 1,
    val favorite: Boolean = false, val pools: List<String> = emptyList(),
    val blocked: Boolean = false, val previewMedia: MediaFile? = null, val format: String = "", val filterFields: Map<String, String> = emptyMap(), val needsVerification: Boolean = false
) {
    val key get() = "${source.name}:$id"
    val submissionUrl get() = when (source) {
        Source.E621 -> "https://e621.net/posts/$id"
        Source.INKBUNNY -> "https://inkbunny.net/s/$id"
    }
}

enum class GalleryMode { EXPLORE, FAVORITES, FOLLOWING, LOCAL }
data class SearchQuery(val e621: String = "", val inkbunny: String = "", val inkArtist: String = "", val mode: GalleryMode = GalleryMode.EXPLORE, val enabled: Set<Source> = Source.entries.toSet())
data class Page(val items: List<Artwork>, val more: Boolean, val resultId: String? = null)

fun mergeArtwork(old: List<Artwork>, new: List<Artwork>): List<Artwork> =
    (old + new).associateBy { it.key }.values.filterNot { it.blocked }.sortedWith(compareByDescending<Artwork> { it.uploadedAt }.thenBy { it.key })

/** Keep already displayed slots fixed; only the newly settled batch is sorted. */
fun appendArtworkBatch(old: List<Artwork>, fresh: List<Artwork>): List<Artwork> {
    val updates = fresh.associateBy { it.key }
    val existing = old.map { it.key }.toSet()
    return old.map { updates[it.key] ?: it }.filterNot { it.blocked } +
        mergeArtwork(emptyList(), fresh.filterNot { it.key in existing })
}
