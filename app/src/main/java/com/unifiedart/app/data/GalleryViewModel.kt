package com.unifiedart.app.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SourceState(val page: Int = 1, val more: Boolean = true, val loading: Boolean = false,
    val error: String? = null, val resultId: String? = null)
data class GalleryState(val query: SearchQuery = SearchQuery(), val items: List<Artwork> = emptyList(),
    val sources: Map<Source, SourceState> = Source.entries.associateWith { SourceState() }, val started: Boolean = false) {
    val loading get() = sources.values.any { it.loading }
}

/** Each source publishes as soon as it finishes. Failure never discards the other source's results. */
class GalleryViewModel : ViewModel() {
    private val adapters: Map<Source, ArtSource> = listOf(E621Source(), InkbunnySource()).associateBy { it.source }
    private val mutable = MutableStateFlow(GalleryState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0

    init { search(SearchQuery()) }

    fun search(query: SearchQuery) {
        job?.cancel()
        generation++
        mutable.value = GalleryState(query = query, started = true)
        loadNext()
    }
    fun refresh() = search(mutable.value.query)
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
    suspend fun details(art: Artwork): Artwork = adapters.getValue(art.source).details(art)
}
