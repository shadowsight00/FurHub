package com.unifiedart.app

import android.graphics.drawable.Animatable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
        VideoPlayer(art.previewMedia.url, art.source, preview = true, onError = { videoFailed = true })
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
