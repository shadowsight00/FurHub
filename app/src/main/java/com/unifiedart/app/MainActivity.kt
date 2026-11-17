package com.unifiedart.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.unifiedart.app.data.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBFA5FF),
                background = Color(0xFF101014), surface = Color(0xFF19191F))) {
                Surface(Modifier.fillMaxSize()) { Gallery() }
            }
        }
    }
}

fun imageRequest(context: android.content.Context, art: Artwork, url: String): ImageRequest {
    val accounts = Accounts.get(context)
    val scope = if (art.source == Source.E621) accounts.eId.ifBlank { "public" } else accounts.inkId.ifBlank { "guest" }
    val privateAsset = url.contains("/private_files/") || url.contains("/private_thumbnails/")
    return ImageRequest.Builder(context).data(accounts.mediaUrl(url))
        .memoryCacheKey("${art.key}:$scope:$url").diskCacheKey("${art.key}:$scope:$url")
        .diskCachePolicy(if (privateAsset) coil.request.CachePolicy.DISABLED else coil.request.CachePolicy.ENABLED)
        .addHeader("User-Agent", USER_AGENT)
        .apply { if (art.source == Source.INKBUNNY) addHeader("Referer", "https://inkbunny.net/") }
        .crossfade(true).build()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Gallery(model: GalleryViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var activeField by remember { mutableStateOf("") }
    var eSuggestions by remember { mutableStateOf(emptyList<String>()) }
    var iSuggestions by remember { mutableStateOf(emptyList<String>()) }
    var eTags by rememberSaveable { mutableStateOf("") }
    var iTags by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var columns by rememberSaveable { mutableIntStateOf(model.local.columns) }
    var newestFirst by rememberSaveable { mutableStateOf(model.local.newestFirst) }
    val displayItems = if (newestFirst) state.items else state.items.asReversed()
    var settings by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var accountSettings by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    val notice by model.notice.collectAsState()
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    val playback by model.local.playback.collectAsState()
    val wifi = isOnWifi()
    val autoplay = !playback.second && playback.first != "never" && (playback.first != "wifi" || wifi)
    val artworkByKey = remember(state.items) { state.items.associateBy { it.key } }
    val activePreviews by remember(artworkByKey) { derivedStateOf {
        grid.layoutInfo.visibleItemsInfo.map { it.key.toString() }.filter { key ->
            artworkByKey[key]?.let { it.previewMedia != null || it.thumbnail.substringBefore('?').endsWith(".gif", true) } == true
        }.take(2).toSet()
    } }
    val selected = displayItems.indexOfFirst { it.key == selectedKey }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("gallery", 0) }
    var historyOpen by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(preferences.getString("history", "")!!.split('\n').filter { it.contains('\t') }) }
    fun submit() {
        activeField = ""; focus.clearFocus()
        val record = eTags.replace('\n', ' ').replace('\t', ' ') + "\t" + iTags.replace('\n', ' ').replace('\t', ' ')
        history = (listOf(record) + history).distinct().take(12)
        preferences.edit().putString("history", history.joinToString("\n")).apply()
        model.search(SearchQuery(eTags, iTags))
    }
    LaunchedEffect(eTags, activeField, section, expanded) {
        eSuggestions = emptyList()
        if (activeField == "e" && section == 0 && expanded && eTags.substringAfterLast(' ').length >= 2) {
            delay(650)
            try { eSuggestions = model.suggestions(Source.E621, eTags) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
        }
    }
    LaunchedEffect(iTags, activeField, section, expanded) {
        iSuggestions = emptyList()
        if (activeField == "i" && section == 0 && expanded && iTags.length >= 2) {
            delay(650)
            try { iSuggestions = model.suggestions(Source.INKBUNNY, iTags) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
        }
    }
    val nextThumbnails by remember(displayItems) { derivedStateOf {
        val next = (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) + 1
        if (section < 3 && !playback.second && selectedKey == null) displayItems.subList(next.coerceAtMost(displayItems.size), (next + 6).coerceAtMost(displayItems.size)).filterNot { it.blocked || it.needsVerification } else emptyList()
    } }
    DisposableEffect(nextThumbnails, context) {
        val requests = nextThumbnails.map { coil.Coil.imageLoader(context).enqueue(imageRequest(context, it, it.thumbnail).newBuilder().size(256).build()) }
        onDispose { requests.forEach { it.dispose() } }
    }
    val nearEnd by remember { derivedStateOf {
        val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        (state.items.isEmpty() || last >= state.items.size - 10) &&
            state.sources.values.any { it.more && it.error == null }
    } }
    LaunchedEffect(nearEnd, state.items.size, state.loading) {
        if (nearEnd && !state.loading) model.loadNext()
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("UnifiedArt", style = MaterialTheme.typography.headlineSmall)
                Text("One library. Two sources.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide search" else "Search") }
            TextButton(onClick = { settings = true }) { Text("Grid") }
        }
        ScrollableTabRow(section, edgePadding = 0.dp) {
            listOf("Explore", "Favorites", "Following", "Collections", "Downloads", "Settings").forEachIndexed { index, label ->
                Tab(selected = section == index, onClick = {
                    section = index; selectedKey = null
                    when (index) {
                        0 -> model.search(SearchQuery(eTags, iTags))
                        1 -> model.search(SearchQuery(mode = GalleryMode.FAVORITES))
                        2 -> model.search(SearchQuery(mode = GalleryMode.FOLLOWING))
                    }
                }, text = { Text(label) })
            }
        }
        if (notice.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { model.notice.value = "" }) { Text("Dismiss") }
        }
        if (section == 3) CollectionsPanel(model) { section = 0 }
        else if (section == 4) DownloadsPanel(model)
        else if (section == 5) Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton(onClick = { accountSettings = true }) { Text("Connected accounts") }
            TextButton(onClick = { settings = true }) { Text("Gallery & animated previews") }
            Text("Account credentials are encrypted with Android Keystore. No passwords are saved. The app adds no rating blacklist.", Modifier.padding(12.dp))
        }
        else {
        if (state.query.mode == GalleryMode.FAVORITES) Text("Account favorites · both sources", style = MaterialTheme.typography.titleSmall)
        if (state.query.mode in setOf(GalleryMode.FAVORITES, GalleryMode.FOLLOWING)) Row {
            TextButton(onClick = { model.refresh() }) { Text("Refresh") }
            if (state.loading) TextButton(onClick = { model.cancel() }) { Text("Cancel") }
        }
        if (state.query.mode == GalleryMode.FOLLOWING) Text("Followed e621 artists + Inkbunny unread submissions", style = MaterialTheme.typography.labelSmall)
        if (expanded && state.query.mode == GalleryMode.EXPLORE) {
            OutlinedTextField(eTags, { eTags = it }, label = { Text("e621 tags & syntax") },
                singleLine = true, modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) activeField = "e" },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
            if (eSuggestions.isNotEmpty() && activeField == "e") Row(Modifier.horizontalScroll(rememberScrollState())) {
                eSuggestions.forEach { suggestion -> TextButton(onClick = {
                    val prefix = eTags.substringAfterLast(' ').takeWhile { it == '-' || it == '~' }
                    val before = eTags.substringBeforeLast(' ', "")
                    eTags = (if (before.isEmpty()) "" else "$before ") + prefix + suggestion
                    activeField = ""; focus.clearFocus()
                }) { Text(suggestion) } }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(iTags, { iTags = it }, label = { Text("Inkbunny keywords") },
                singleLine = true, modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) activeField = "i" },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
            if (iSuggestions.isNotEmpty() && activeField == "i") Row(Modifier.horizontalScroll(rememberScrollState())) {
                iSuggestions.forEach { suggestion -> TextButton(onClick = { iTags = suggestion; activeField = ""; focus.clearFocus() }) { Text(suggestion) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { submit() }) { Text("Search") }
                TextButton(onClick = { model.refresh() }) { Text("Refresh") }
                TextButton(onClick = { model.saveSearch((eTags + " / " + iTags).take(80).ifBlank { "Latest artwork" }) }) { Text("Save") }
                if (state.loading) TextButton(onClick = { model.cancel() }) { Text("Cancel") }
            }
            if (history.isNotEmpty()) TextButton(onClick = { historyOpen = true }) { Text("Search history") }
            if (state.items.isEmpty() && history.isNotEmpty() && !state.loading) {
                TextButton(onClick = {
                    val parts = history.first().split('\t', limit = 2)
                    eTags = parts[0]; iTags = parts.getOrElse(1) { "" }; submit()
                }) { Text("Repeat last search") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${state.items.size} artworks · ${if (newestFirst) "newest" else "oldest"} loaded first", style = MaterialTheme.typography.labelSmall)
            if (state.query.inkArtist.isNotEmpty()) TextButton(onClick = { model.search(SearchQuery(eTags, iTags)) }) { Text("All artists") }
        }
        if (selection.isNotEmpty()) Row {
            TextButton(onClick = { state.items.filter { it.key in selection }.forEach { model.queueDownload(it) }; selection = emptySet() }) { Text("Download ${selection.size} selected") }
            TextButton(onClick = { selection = emptySet() }) { Text("Clear selection") }
        }
        state.sources.forEach { (source, cursor) ->
            cursor.error?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${source.label}: $message", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { model.retry(source) }, enabled = !state.loading) { Text("Retry") }
                }
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.loading && state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No artwork loaded. Change your search or retry a source.", modifier = Modifier.padding(24.dp))
            }
        } else LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(displayItems, key = { it.key }, contentType = { "artwork" }) { art ->
                Box(Modifier.aspectRatio(0.82f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .combinedClickable(onClick = {
                        if (selection.isEmpty()) selectedKey = art.key
                        else selection = if (art.key in selection) selection - art.key else selection + art.key
                    }, onLongClick = { selection = if (art.key in selection) selection - art.key else selection + art.key })) {
                    if (art.needsVerification) Text("Saved artwork · tap to load", Modifier.padding(8.dp))
                    else AnimatedThumbnail(art, art.key in activePreviews && selectedKey == null, autoplay && section < 3 && selectedKey == null)
                    if (art.key in selection) Text("✓", Modifier.align(Alignment.TopEnd).padding(8.dp), color = MaterialTheme.colorScheme.primary)
                    Surface(Modifier.align(Alignment.BottomStart), color = Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(4.dp)) {
                        Text(buildString {
                            append(art.source.label)
                            if (art.favorite) append(" ★")
                            if (state.query.mode == GalleryMode.FOLLOWING && model.local.wasViewed(art.key)) append(" · viewed")
                            if (art.format.isNotBlank()) append(" · ${art.format.uppercase()}")
                            if (art.pageCount > 1) append(" · ${art.pageCount} pages")
                        }, Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (state.sources.values.any { it.more && it.error == null }) TextButton(onClick = { model.loadNext() }, enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth()) { Text(if (state.loading) "Loading…" else "Load more") }
                else Text("End of available results", Modifier.padding(16.dp))
            }
        }
        }
    }
    if (historyOpen) AlertDialog(onDismissRequest = { historyOpen = false }, title = { Text("Recent searches") }, text = {
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            history.forEach { record -> TextButton(onClick = {
                val parts = record.split('\t', limit = 2); eTags = parts[0]; iTags = parts.getOrElse(1) { "" }
                historyOpen = false; submit()
            }) { Text(record.replace("\t", " / ")) } }
        }
    }, confirmButton = { TextButton(onClick = { historyOpen = false }) { Text("Close") } })
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Gallery layout") },
        text = { Column {
            Text("$columns columns")
            Slider(columns.toFloat(), { columns = it.toInt(); model.local.columns = columns }, valueRange = 2f..6f, steps = 3)
            TextButton(onClick = { newestFirst = !newestFirst; model.local.newestFirst = newestFirst }) { Text(if (newestFirst) "Sort: newest first" else "Sort: oldest first") }
            Text("Animated previews")
            Row {
                listOf("always" to "On", "never" to "Off", "wifi" to "Wi-Fi").forEach { (value, label) ->
                    TextButton(onClick = { model.local.setPlayback(value, playback.second) }) { Text(if (playback.first == value) "✓ $label" else label) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(playback.second, { model.local.setPlayback(playback.first, it) })
                Text("Reduced data usage")
            }
            Text("Phase 3 · Account connections & merged favorites\nFollowing & animated previews. Background downloads & local collections.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
    if (accountSettings) AccountSettings(model) { accountSettings = false }
    if (selected >= 0) MediaViewer(displayItems, selected, model, onClose = { selectedKey = null }, onArtist = { art, artist ->
        selectedKey = null
        if (art.source == Source.E621) { eTags = artist; iTags = ""; model.search(SearchQuery(e621 = artist, enabled = setOf(Source.E621))) }
        else { eTags = artist; iTags = ""; model.search(SearchQuery(inkArtist = artist, enabled = setOf(Source.INKBUNNY))) }
    }, onTag = { art, tag ->
        selectedKey = null
        if (art.source == Source.E621) eTags = tag else iTags = tag
        model.search(SearchQuery(eTags, iTags))
    }, onPool = { id -> selectedKey = null; eTags = "pool:$id"; model.search(SearchQuery(e621 = eTags, enabled = setOf(Source.E621))) })
}
