package com.unifiedart.app

import android.graphics.drawable.Animatable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.unifiedart.app.data.*

@Composable
fun isOnWifi(): Boolean {
    val context = LocalContext.current
    var wifi by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun update() { wifi = manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { update() }
            override fun onLost(network: Network) { update() }
        }
        update(); manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return wifi
}

@Composable
fun AnimatedThumbnail(art: Artwork, active: Boolean, allowed: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var videoFailed by remember(art.key) { mutableStateOf(false) }
    if (active && allowed && art.previewMedia?.video == true && !videoFailed) {
        VideoPreview(art, art.previewMedia.url, onError = { videoFailed = true })
    } else SubcomposeAsyncImage(imageRequest(context, art, art.thumbnail), art.title,
        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp)) } },
        error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Preview unavailable", style = MaterialTheme.typography.labelSmall) } },
        success = { state ->
            DisposableEffect(state.result.drawable, active, allowed, owner) {
                val animation = state.result.drawable as? Animatable
                fun update() { if (active && allowed && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) animation?.start() else animation?.stop() }
                val observer = LifecycleEventObserver { _, _ -> update() }
                owner.lifecycle.addObserver(observer); update()
                onDispose { animation?.stop(); owner.lifecycle.removeObserver(observer) }
            }
            SubcomposeAsyncImageContent()
        })
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPreview(art: Artwork, url: String, onError: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(art.key, url) {
        val source = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT).setDefaultRequestProperties(
            if (art.source == Source.INKBUNNY) mapOf("Referer" to "https://inkbunny.net") else emptyMap())
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(source)).build().apply {
            volume = 0f; repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(Accounts.get(context).mediaUrl(url))); prepare()
            playWhenReady = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
    }
    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
            if (event == Lifecycle.Event.ON_START) player.play()
        }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { onError() }
        }
        player.addListener(listener); owner.lifecycle.addObserver(observer)
        onDispose { player.removeListener(listener); owner.lifecycle.removeObserver(observer); player.release() }
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false; layoutParams = ViewGroup.LayoutParams(-1, -1) } }, modifier = Modifier.fillMaxSize())
}
