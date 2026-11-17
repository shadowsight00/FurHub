package com.unifiedart.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import org.json.JSONObject
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.workDataOf
import java.util.UUID

data class SourceState(val page: Int = 1, val more: Boolean = true, val loading: Boolean = false,
    val error: String? = null, val resultId: String? = null)
data class GalleryState(val query: SearchQuery = SearchQuery(), val items: List<Artwork> = emptyList(),
    val sources: Map<Source, SourceState> = Source.entries.associateWith { SourceState() }, val started: Boolean = false) {
    val loading get() = sources.values.any { it.loading }
}

/** Each source publishes as soon as it finishes. Failure never discards the other source's results. */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    val accounts = Accounts.get(application)
    val local = LocalState(application)
    val database = LibraryDatabase.get(application)
    val records = database.dao().observeRecords().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val downloads = database.dao().observeDownloads().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val e621 = E621Source(accounts = accounts, follows = { local.followedArtists })
    private val adapters: Map<Source, ArtSource> = listOf(e621, InkbunnySource(accounts = accounts)).associateBy { it.source }
    private val mutable = MutableStateFlow(GalleryState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    val notice = MutableStateFlow("")
    fun queueDownload(art: Artwork, fileIndex: Int = -1) = queueTask("submission", art.title, art.toJson(), fileIndex)
    fun queuePool(id: String) = queueTask("pool", "e621 pool #$id ZIP", id, -1)
    private fun queueTask(kind: String, label: String, payload: String, fileIndex: Int) {
        val directory = local.downloadDirectory
        if (directory.isEmpty()) { notice.value = "Choose a folder in Downloads first"; return }
        viewModelScope.launch {
            val task = DownloadTask(UUID.randomUUID().toString(), kind, label, payload, directory, fileIndex)
            database.dao().put(task); enqueue(task.id); notice.value = "Download queued"
        }
    }
    private fun enqueue(id: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("task" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
    }
    fun cancelDownload(id: String) {
        WorkManager.getInstance(getApplication()).cancelUniqueWork(id)
        viewModelScope.launch {
            database.dao().download(id)?.let { database.dao().put(it.copy(state = "Canceled", message = "Canceled by you")) }
        }
    }
    fun retryDownload(task: DownloadTask) { viewModelScope.launch {
        val retry = task.copy(id = UUID.randomUUID().toString(), state = "Queued", done = 0, total = 0, bytes = 0, message = "", outputUri = "", created = System.currentTimeMillis())
        database.dao().put(retry); enqueue(retry.id)
    } }

    init { search(SearchQuery()) }

    fun search(query: SearchQuery) {
        job?.cancel()
        generation++
        mutable.value = GalleryState(query = query, started = true)
        val stamp = generation
        if (query.mode == GalleryMode.FOLLOWING) viewModelScope.launch {
            val scope = accounts.eId.ifBlank { "public" }
            val cached = database.dao().records("feed").filter { it.groupName == scope }.mapNotNull {
                runCatching { artworkFromJson(it.payload).copy(needsVerification = true) }.getOrNull()
            }.map { if (it.source == Source.E621) it.copy(blocked = blacklisted(it, accounts.eBlacklist)) else it }
            if (generation == stamp) mutable.update { it.copy(items = mergeArtwork(cached, it.items)) }
        }
        loadNext()
    }
    fun refresh() {
        if (mutable.value.query.mode == GalleryMode.LOCAL) mutable.update { state -> state.copy(items = state.items.map { it.copy(needsVerification = true, blocked = if (it.source == Source.E621) blacklisted(it, accounts.eBlacklist) else it.blocked) }.filterNot { it.blocked }) }
        else search(mutable.value.query)
    }
    fun cancel() {
        generation++
        job?.cancel()
        mutable.update { it.copy(sources = it.sources.mapValues { (_, s) -> s.copy(loading = false) }) }
    }
    fun retry(source: Source) {
        if (mutable.value.loading) return
        mutable.update { it.copy(sources = it.sources + (source to it.sources.getValue(source).copy(error = null))) }
        loadNext(setOf(source))
    }
    fun loadNext(only: Set<Source> = Source.entries.toSet()) {
        if (mutable.value.loading) return
        val current = generation
        val snapshot = mutable.value
        val eligible = only.filter { snapshot.sources.getValue(it).let { s -> s.more && s.error == null } }
        if (eligible.isEmpty()) return
        mutable.update { it.copy(sources = it.sources.mapValues { (source, value) -> if (source in eligible) value.copy(loading = true) else value }) }
        job = viewModelScope.launch {
            supervisorScope {
                eligible.forEach { source -> launch {
                    val cursor = snapshot.sources.getValue(source)
                    try {
                        val page = adapters.getValue(source).search(snapshot.query, cursor.page, cursor.resultId)
                        if (generation == current) mutable.update { state -> state.copy(
                            items = mergeArtwork(state.items, page.items), sources = state.sources +
                                (source to SourceState(cursor.page + 1, page.more, resultId = page.resultId))) }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) {
                        if (generation == current) mutable.update { it.copy(sources = it.sources +
                            (source to cursor.copy(error = error.message ?: "Connection failed", loading = false))) }
                    }
                } }
            }
        }
    }
    fun saveSearch(label: String) { viewModelScope.launch {
        val query = mutable.value.query
        database.dao().put(LibraryRecord("search:$label", "search", label, JSONObject().put("e621", query.e621).put("inkbunny", query.inkbunny).toString())); notice.value = "Search saved"
    } }
    fun saveArt(art: Artwork, group: String = "") { viewModelScope.launch {
        val kind = if (group.isBlank()) "bookmark" else "item"
        database.dao().put(LibraryRecord("$kind:$group:${art.key}", kind, art.title, art.toJson(), group)); notice.value = "Saved locally"
    } }
    suspend fun suggestions(source: Source, text: String): List<String> = when (source) {
        Source.E621 -> e621.suggestions(text)
        Source.INKBUNNY -> (adapters.getValue(source) as InkbunnySource).suggestions(text)
    }
    fun saveArtist(source: Source, artist: String) { viewModelScope.launch {
        database.dao().put(LibraryRecord("artist:${source.name}:$artist", "artist", artist, source.name))
        notice.value = "Artist saved locally"
    } }
    fun syncWatchedArtists() { viewModelScope.launch {
        try {
            val names = (adapters.getValue(Source.INKBUNNY) as InkbunnySource).watchedArtists()
            val scope = accounts.inkId
            database.dao().records("watch").filter { it.groupName == scope }.forEach { database.dao().remove(it.key) }
            names.forEach { database.dao().put(LibraryRecord("watch:$scope:$it", "watch", it, Source.INKBUNNY.name, scope)) }
            notice.value = "Watched artists refreshed"
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { notice.value = "Couldn't load watched artists. Check your Inkbunny connection." }
    } }
    fun savePool(id: String) { viewModelScope.launch { database.dao().put(LibraryRecord("pool:$id", "pool", "Pool #$id", id)) } }
    fun createCollection(name: String) { if (name.isNotBlank()) viewModelScope.launch {
        database.dao().put(LibraryRecord("collection:$name", "collection", name))
    } }
    fun removeRecord(key: String) { viewModelScope.launch { database.dao().remove(key) } }
    fun openCollection(kind: String, group: String = "") {
        cancel()
        val stamp = generation
        viewModelScope.launch {
            val items = database.dao().records(kind).filter { group.isEmpty() || it.groupName == group }.mapNotNull {
                runCatching { artworkFromJson(it.payload).copy(needsVerification = true) }.getOrNull()
            }.map { if (it.source == Source.E621) it.copy(blocked = blacklisted(it, accounts.eBlacklist)) else it }
            if (stamp != generation) return@launch
            mutable.value = GalleryState(query = SearchQuery(mode = GalleryMode.LOCAL), items = mergeArtwork(emptyList(), items),
                sources = Source.entries.associateWith { SourceState(more = false) }, started = true)
        }
    }
    suspend fun details(art: Artwork): Artwork {
        val detail = adapters.getValue(art.source).details(art)
        local.markViewed(art.key)
        mutable.update { state -> state.copy(items = state.items.map { if (it.key == detail.key) detail else it }.filterNot { it.blocked }) }
        return detail
    }
    fun toggleArtist(artist: String) {
        local.toggleArtist(artist)
        if (mutable.value.query.mode == GalleryMode.FOLLOWING) refresh()
    }
    suspend fun favorite(art: Artwork, desired: Boolean) {
        if (art.source == Source.INKBUNNY) throw IllegalStateException("Inkbunny's documented API supports reading favorites, but doesn't document adding/removing them. Local bookmarks are planned separately.")
        mutable.update { s -> s.copy(items = s.items.map { if (it.key == art.key) it.copy(favorite = desired) else it }) }
        try { e621.favorite(art, desired) }
        catch (error: Exception) {
            mutable.update { s -> s.copy(items = s.items.map { if (it.key == art.key) it.copy(favorite = art.favorite) else it }) }
            throw error
        }
    }
}
