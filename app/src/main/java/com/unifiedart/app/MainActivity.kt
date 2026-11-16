package com.unifiedart.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBFA5FF),
                background = Color(0xFF101014), surface = Color(0xFF19191F))) {
                Surface(Modifier.fillMaxSize()) { Gallery() }
            }
        }
    }
}

fun imageRequest(context: android.content.Context, art: Artwork, url: String) = ImageRequest.Builder(context)
    .data(Accounts.get(context).mediaUrl(url)).memoryCacheKey(art.key + ":" + url).diskCacheKey(art.key + ":" + url).addHeader("User-Agent", USER_AGENT)
    .apply { if (art.source == Source.INKBUNNY) addHeader("Referer", "https://inkbunny.net") }
    .crossfade(true).build()

@Composable
private fun Gallery(model: GalleryViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var eTags by rememberSaveable { mutableStateOf("") }
    var iTags by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    var settings by remember { mutableStateOf(false) }
    var accountSettings by remember { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    val selected = state.items.indexOfFirst { it.key == selectedKey }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("gallery", 0) }
    var history by remember { mutableStateOf(preferences.getString("history", "")!!.split('\n').filter { it.contains('\t') }) }
    fun submit() {
        val record = eTags.replace('\n', ' ').replace('\t', ' ') + "\t" + iTags.replace('\n', ' ').replace('\t', ' ')
        history = (listOf(record) + history).distinct().take(12)
        preferences.edit().putString("history", history.joinToString("\n")).apply()
        model.search(SearchQuery(eTags, iTags))
    }
    val nearEnd by remember { derivedStateOf {
        val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        state.items.isNotEmpty() && last >= state.items.size - 10
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { model.search(SearchQuery(eTags, iTags)) }) { Text("Explore") }
            TextButton(onClick = { model.search(SearchQuery(mode = GalleryMode.FAVORITES)) }) { Text("Favorites") }
            TextButton(onClick = { accountSettings = true }) { Text("Accounts") }
        }
        if (state.query.mode == GalleryMode.FAVORITES) Text("Account favorites · both sources", style = MaterialTheme.typography.titleSmall)
        if (expanded && state.query.mode == GalleryMode.EXPLORE) {
            OutlinedTextField(eTags, { eTags = it }, label = { Text("e621 tags & syntax") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(iTags, { iTags = it }, label = { Text("Inkbunny keywords") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { submit() }) { Text("Search library") }
                TextButton(onClick = { model.refresh() }) { Text("Refresh") }
                if (state.loading) TextButton(onClick = { model.cancel() }) { Text("Cancel") }
            }
            if (state.items.isEmpty() && history.isNotEmpty() && !state.loading) {
                TextButton(onClick = {
                    val parts = history.first().split('\t', limit = 2)
                    eTags = parts[0]; iTags = parts.getOrElse(1) { "" }; submit()
                }) { Text("Repeat last search") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${state.items.size} artworks · newest loaded first", style = MaterialTheme.typography.labelSmall)
            if (state.query.inkArtist.isNotEmpty()) TextButton(onClick = { model.search(SearchQuery(eTags, iTags)) }) { Text("All artists") }
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
            items(state.items, key = { it.key }, contentType = { "artwork" }) { art ->
                Box(Modifier.aspectRatio(0.82f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .clickable { selectedKey = art.key }) {
                    if (art.blocked) Text("Blocked by source preferences", modifier = Modifier.padding(8.dp))
                    else SubcomposeAsyncImage(imageRequest(context, art, art.thumbnail), art.title,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp)) } },
                        error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Preview unavailable", style = MaterialTheme.typography.labelSmall) } })
                    Surface(Modifier.align(Alignment.BottomStart), color = Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(4.dp)) {
                        Text(buildString {
                            append(art.source.label)
                            art.files.firstOrNull()?.let { append(" · ${it.label}") }
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
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Gallery layout") },
        text = { Column {
            Text("$columns columns")
            Slider(columns.toFloat(), { columns = it.toInt() }, valueRange = 2f..6f, steps = 3)
            Text("Phase 2 · Account connections & merged favorites\nFollowing, downloads and collections are planned next.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
    if (accountSettings) AccountSettings(model) { accountSettings = false }
    if (selected >= 0) MediaViewer(state.items, selected, model, onClose = { selectedKey = null }, onArtist = { art, artist ->
        selectedKey = null
        if (art.source == Source.E621) { eTags = artist; iTags = ""; model.search(SearchQuery(e621 = artist, inkbunny = artist)) }
        else { eTags = artist; iTags = ""; model.search(SearchQuery(e621 = artist, inkArtist = artist)) }
    }, onTag = { art, tag ->
        selectedKey = null
        if (art.source == Source.E621) eTags = tag else iTags = tag
        model.search(SearchQuery(eTags, iTags))
    }, onPool = { id -> selectedKey = null; eTags = "pool:$id"; model.search(SearchQuery(e621 = eTags)) })
}
