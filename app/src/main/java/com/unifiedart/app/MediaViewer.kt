package com.unifiedart.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.ImageDecoderDecoder
import coil.decode.GifDecoder
import com.unifiedart.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MediaViewer(items: List<Artwork>, initial: Int, model: GalleryViewModel, onClose: () -> Unit,
    onArtist: (Artwork, String) -> Unit, onTag: (Artwork, String) -> Unit, onPool: (String) -> Unit) {
    val pager = rememberPagerState(initialPage = initial, pageCount = { items.size })
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("Back") }
                    Text("${pager.currentPage + 1} / ${items.size}", style = MaterialTheme.typography.labelMedium)
                }
                HorizontalPager(pager, modifier = Modifier.weight(1f), key = { items[it].key }, beyondViewportPageCount = 0) { index ->
                    val original = items[index]
                    var art by remember(original.key) { mutableStateOf(original) }
                    var error by remember(original.key) { mutableStateOf<String?>(null) }
                    var loading by remember(original.key) { mutableStateOf(original.needsVerification || original.source != Source.E621) }
                    var verified by remember(original.key) { mutableStateOf(!original.needsVerification && original.source == Source.E621 && !original.blocked) }
                    var retry by remember(original.key) { mutableIntStateOf(0) }
                    var fileIndex by remember(original.key) { mutableIntStateOf(0) }
                    var poolInfo by remember { mutableStateOf<org.json.JSONObject?>(null) }
                    var info by remember(original.key) { mutableStateOf(false) }
                    var chooseCollection by remember { mutableStateOf(false) }
                    var actionBusy by remember(original.key) { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()
                    val libraryRecords by model.records.collectAsState()
                    LaunchedEffect(original.key, retry) {
                        loading = !verified; error = null
                        try { art = model.details(original); verified = true }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { error = "Couldn't load this submission. It may be unavailable to this session." }
                        finally { loading = false }
                    }
                    poolInfo?.let { pool ->
                        AlertDialog(onDismissRequest = { poolInfo = null }, title = { Text(pool.optString("name").replace('_', ' ')) }, text = {
                            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                                Text("${pool.optJSONArray("post_ids")?.length() ?: 0} posts · e621 pool #${pool.optString("id")}")
                                Text(pool.optString("description"))
                            }
                        }, confirmButton = { TextButton(onClick = { poolInfo = null; onPool(pool.optString("id")) }) { Text("Browse pool") } },
                            dismissButton = { TextButton(onClick = { model.queuePool(pool.optString("id")); poolInfo = null }) { Text("Download ZIP") } })
                    }
                    Column(Modifier.fillMaxSize()) {
                        Text("${art.source.label} · ${art.title}", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error?.let { message ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(message, Modifier.weight(1f).padding(10.dp), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { retry++ }) { Text("Retry") }
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val file = art.files.getOrNull(fileIndex)
                            when {
                                loading && !verified -> CircularProgressIndicator()
                                !verified -> Text("Connect to the source to check media availability.", Modifier.padding(20.dp))
                                art.blocked -> Text("This artwork is blocked by your source preferences.", Modifier.padding(20.dp))
                                file == null && !loading -> Text("No media is available to this session.", Modifier.padding(20.dp))
                                file?.video == true -> VideoPlayer(file.url, art.source, active = pager.settledPage == index)
                                file?.image == true -> ZoomImage(art, file.url)
                                file != null -> Text("${file.label} playback isn't supported on this device.", Modifier.padding(20.dp))
                            }
                        }
                        if (art.files.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { fileIndex-- }, enabled = fileIndex > 0) { Text("Previous page") }
                            Text("${fileIndex + 1}/${art.files.size}")
                            TextButton(onClick = { fileIndex++ }, enabled = fileIndex < art.files.lastIndex) { Text("Next page") }
                        }
                        if (art.source == Source.INKBUNNY) Text("Inkbunny site favorites are read-only through its documented API. Use Bookmark to save locally.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                        else TextButton(onClick = {
                            if (!actionBusy) {
                                val before = art
                                val desired = !art.favorite
                                art = art.copy(favorite = desired); actionBusy = true; error = null
                                scope.launch {
                                    try { model.favorite(before, desired) }
                                    catch (cancel: CancellationException) { throw cancel }
                                    catch (failure: Exception) { art = before; error = failure.message ?: "Favorite couldn't sync. Refresh to retry." }
                                    finally { actionBusy = false }
                                }
                            }
                        }, enabled = !actionBusy) { Text(if (art.favorite) "★ Favorited" else "☆ Favorite") }
                        val notice by model.notice.collectAsState()
                        if (notice.isNotEmpty()) Text(notice, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { model.queueDownload(art, fileIndex) }) { Text("Download") }
                            TextButton(onClick = {
                                val key = "bookmark::${art.key}"
                                if (libraryRecords.any { it.key == key }) model.removeRecord(key) else model.saveArt(art)
                            }) { Text(if (libraryRecords.any { it.key == "bookmark::${art.key}" }) "Remove bookmark" else "Bookmark") }
                            TextButton(onClick = { chooseCollection = true }) { Text("Add to collection") }
                        }
                        if (chooseCollection) {
                            val records by model.records.collectAsState()
                            var name by remember { mutableStateOf("") }
                            AlertDialog(onDismissRequest = { chooseCollection = false }, title = { Text("Save to collection") }, text = {
                                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                                    records.filter { it.kind == "collection" }.forEach { record ->
                                        val key = "item:${record.label}:${art.key}"
                                        val exists = records.any { it.key == key }
                                        TextButton(onClick = { if (exists) model.removeRecord(key) else model.saveArt(art, record.label); chooseCollection = false }) { Text(if (exists) "Remove from ${record.label}" else record.label) }
                                    }
                                    OutlinedTextField(name, { name = it }, label = { Text("New collection name") })
                                }
                            }, confirmButton = { TextButton(onClick = { model.createCollection(name.trim()); model.saveArt(art, name.trim()); chooseCollection = false }, enabled = name.isNotBlank()) { Text("Create & save") } })
                        }
                        TextButton(onClick = { info = !info }, modifier = Modifier.fillMaxWidth()) { Text(if (info) "Hide tags & details" else "Show tags & details (${art.tags.size})") }
                        if (info) Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Text(art.submissionUrl, style = MaterialTheme.typography.bodySmall)
                            Text("${art.source.label} #${art.id}", style = MaterialTheme.typography.labelLarge)
                            if (art.uploadedAt != 0L) Text(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm z").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(art.uploadedAt)))
                            art.files.getOrNull(fileIndex)?.let { Text("${it.label} · ${it.width} × ${it.height}") }
                            art.artists.forEach { artist ->
                                TextButton(onClick = { onArtist(art, artist) }) { Text("Browse artist: $artist") }
                                TextButton(onClick = { model.saveArtist(art.source, artist) }) { Text("Save favorite artist") }
                                if (art.source == Source.E621) {
                                    val followed by model.local.artists.collectAsState()
                                    TextButton(onClick = { model.toggleArtist(artist) }) { Text(if (artist in followed) "Unfollow $artist" else "Follow $artist") }
                                }
                            }
                            if (art.source == Source.E621) art.pools.forEach { pool -> TextButton(onClick = { onPool(pool) }) { Text("Browse pool #$pool") }; TextButton(onClick = { model.savePool(pool) }) { Text("Save pool #$pool") }; TextButton(onClick = { scope.launch {
                                try { poolInfo = loadPool(pool, model.accounts) }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { error = "Couldn't load pool information" }
                            } }) { Text("Pool information") }; TextButton(onClick = { model.queuePool(pool) }) { Text("Download pool ZIP") } }
                            art.tags.forEach { tag -> TextButton(onClick = { onTag(art, tag) }, contentPadding = PaddingValues(2.dp)) { Text(tag) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomImage(art: Artwork, url: String) {
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    val loader = coil.Coil.imageLoader(context)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val maxX = with(density) { maxWidth.toPx() }
        val maxY = with(density) { maxHeight.toPx() }
        SubcomposeAsyncImage(imageRequest(context, art, url).newBuilder().size(if (scale > 1.5f) coil.size.Size.ORIGINAL else coil.size.Size(maxX.toInt().coerceAtLeast(1), maxY.toInt().coerceAtLeast(1))).placeholderMemoryCacheKey("${art.key}:${if (art.source == Source.E621) Accounts.get(context).eId.ifBlank { "public" } else Accounts.get(context).inkId.ifBlank { "guest" }}:${art.thumbnail}").build(), art.title, imageLoader = loader,
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                .pointerInput(url) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 3f; offset = Offset.Zero }) }
                // At 1x a single-finger drag belongs to the submission pager.
                .pointerInput(url) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2 || scale > 1f) {
                                val pan = event.calculatePan()
                                scale = (scale * event.calculateZoom()).coerceIn(1f, 8f)
                                offset = Offset((offset.x + pan.x).coerceIn(-maxX * (scale - 1) / 2, maxX * (scale - 1) / 2),
                                    (offset.y + pan.y).coerceIn(-maxY * (scale - 1) / 2, maxY * (scale - 1) / 2))
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Image couldn't load. Reopen it to retry.") } })
    }
}
