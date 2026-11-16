package com.unifiedart.app

import android.view.ViewGroup
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.ImageDecoderDecoder
import coil.decode.GifDecoder
import com.unifiedart.app.data.*
import kotlinx.coroutines.CancellationException
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
                    var loading by remember(original.key) { mutableStateOf(false) }
                    var retry by remember(original.key) { mutableIntStateOf(0) }
                    var fileIndex by remember(original.key) { mutableIntStateOf(0) }
                    var info by remember(original.key) { mutableStateOf(false) }
                    LaunchedEffect(original.key, retry) {
                        loading = true; error = null
                        try { art = model.details(original) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { error = "Couldn't load this submission. It may be unavailable to this session." }
                        finally { loading = false }
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
                                art.blocked -> Text("This artwork is blocked by your source preferences.", Modifier.padding(20.dp))
                                file == null && !loading -> Text("No media is available to this session.", Modifier.padding(20.dp))
                                file?.video == true -> Video(file.url, art.source, active = pager.currentPage == index)
                                file?.image == true -> ZoomImage(art, file.url)
                                file != null -> Text("${file.label} playback isn't supported on this device.", Modifier.padding(20.dp))
                            }
                        }
                        if (art.files.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { fileIndex-- }, enabled = fileIndex > 0) { Text("Previous page") }
                            Text("${fileIndex + 1}/${art.files.size}")
                            TextButton(onClick = { fileIndex++ }, enabled = fileIndex < art.files.lastIndex) { Text("Next page") }
                        }
                        TextButton(onClick = { info = !info }, modifier = Modifier.fillMaxWidth()) { Text(if (info) "Hide details" else "Artists, tags & details") }
                        if (info) Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Text("${art.source.label} #${art.id}", style = MaterialTheme.typography.labelLarge)
                            if (art.uploadedAt != 0L) Text(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm z").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(art.uploadedAt)))
                            art.files.getOrNull(fileIndex)?.let { Text("${it.label} · ${it.width} × ${it.height}") }
                            art.artists.forEach { artist -> TextButton(onClick = { onArtist(art, artist) }) { Text("Browse artist: $artist") } }
                            if (art.source == Source.E621) art.pools.forEach { pool -> TextButton(onClick = { onPool(pool) }) { Text("Browse pool #$pool") } }
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
    val loader = remember(context) { ImageLoader.Builder(context).components {
        if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
    }.build() }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val maxX = with(density) { maxWidth.toPx() }
        val maxY = with(density) { maxHeight.toPx() }
        SubcomposeAsyncImage(imageRequest(context, art, url), art.title, imageLoader = loader,
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

@Composable
private fun Video(url: String, source: Source, active: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(url) {
        val http = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT).setDefaultRequestProperties(
            if (source == Source.INKBUNNY) mapOf("Referer" to "https://inkbunny.net") else emptyMap())
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http)).build().apply {
            setMediaItem(MediaItem.fromUri(url)); prepare()
        }
    }
    var failed by remember(url) { mutableStateOf(false) }
    DisposableEffect(player, owner) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        player.addListener(listener); owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    LaunchedEffect(active) { if (!active) player.pause() }
    if (failed) Text("This video couldn't play on this device.", Modifier.padding(20.dp))
    else AndroidView(factory = { PlayerView(it).apply { this.player = player; layoutParams = ViewGroup.LayoutParams(-1, -1) } },
        modifier = Modifier.fillMaxSize())
}
