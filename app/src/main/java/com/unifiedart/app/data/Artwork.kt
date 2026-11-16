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
    val blocked: Boolean = false, val filterFields: Map<String, String> = emptyMap()
) {
    val key get() = "${source.name}:$id"
    val submissionUrl get() = when (source) {
        Source.E621 -> "https://e621.net/posts/$id"
        Source.INKBUNNY -> "https://inkbunny.net/s/$id"
    }
}

enum class GalleryMode { EXPLORE, FAVORITES }
data class SearchQuery(val e621: String = "", val inkbunny: String = "", val inkArtist: String = "", val mode: GalleryMode = GalleryMode.EXPLORE)
data class Page(val items: List<Artwork>, val more: Boolean, val resultId: String? = null)

fun mergeArtwork(old: List<Artwork>, new: List<Artwork>): List<Artwork> =
    (old + new).distinctBy { it.key }.sortedWith(compareByDescending<Artwork> { it.uploadedAt }.thenBy { it.key })
