package com.unifiedart.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalState(context: Context) {
    private val prefs = context.getSharedPreferences("local_library", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(prefs.getStringSet("artists", emptySet()).orEmpty().toSet())
    val artists = mutable.asStateFlow()
    private val playbackMutable = MutableStateFlow(prefs.getString("autoplay", "always")!! to prefs.getBoolean("reduced", false))
    val playback = playbackMutable.asStateFlow()
    fun setPlayback(policy: String, reduced: Boolean) {
        prefs.edit().putString("autoplay", policy).putBoolean("reduced", reduced).apply()
        playbackMutable.value = policy to reduced
    }
    var columns: Int
        get() = prefs.getInt("columns", 3)
        set(value) { prefs.edit().putInt("columns", value).apply() }
    var newestFirst: Boolean
        get() = prefs.getBoolean("newest", true)
        set(value) { prefs.edit().putBoolean("newest", value).apply() }
    var downloadDirectory: String
        get() = prefs.getString("download_directory", "").orEmpty()
        set(value) { prefs.edit().putString("download_directory", value).apply() }
    val followedArtists get() = mutable.value
    fun toggleArtist(artist: String) {
        val next = if (artist in followedArtists) followedArtists - artist else followedArtists + artist
        prefs.edit().putStringSet("artists", next).apply(); mutable.value = next
    }
    fun markViewed(key: String) {
        val keys = prefs.getString("viewed", "").orEmpty().split('\n').filter { it.isNotEmpty() && it != key }
        prefs.edit().putString("viewed", (listOf(key) + keys).take(5000).joinToString("\n")).apply()
    }
    fun wasViewed(key: String) = key in prefs.getString("viewed", "").orEmpty().split('\n')
}
