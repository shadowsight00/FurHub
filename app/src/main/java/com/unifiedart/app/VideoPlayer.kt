package com.unifiedart.app

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.unifiedart.app.data.Accounts
import com.unifiedart.app.data.Source
import com.unifiedart.app.data.USER_AGENT

/** A view must stop referencing its player before codecs and surfaces are released. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VideoSession(val player: Player) {
    private var view: PlayerView? = null
    private var released = false

    fun attach(next: PlayerView) {
        check(!released)
        if (view !== next) view?.player = null
        next.player = player
        view = next
    }

    fun detach(current: PlayerView) {
        try { current.player = null } catch (error: RuntimeException) {
            Log.w("UnifiedArtVideo", "Video detach failed: ${error.javaClass.simpleName}")
        }
        if (view === current) view = null
    }

    fun release() {
        if (released) return
        released = true
        view?.let(::detach)
        view = null
        try { player.release() } catch (error: RuntimeException) {
            Log.w("UnifiedArtVideo", "Video release failed: ${error.javaClass.simpleName}")
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun createVideoView(context: Context, preview: Boolean): PlayerView =
    (LayoutInflater.from(context).inflate(R.layout.video_player, FrameLayout(context), false) as PlayerView).apply {
        useController = !preview
        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    }

/** Only the visible page owns codecs. Setup failures and playback errors stay inside the viewer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoPlayer(url: String, source: Source, active: Boolean = true,
    preview: Boolean = false, onError: (() -> Unit)? = null) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val mediaUrl = Accounts.get(context).mediaUrl(url)
    var retry by remember(url) { mutableIntStateOf(0) }
    var failure by remember(url, retry) { mutableStateOf<String?>(null) }
    var session by remember(mediaUrl, owner, retry, active, failure) { mutableStateOf<VideoSession?>(null) }
    val reportError by rememberUpdatedState(onError)

    fun fail(code: String) {
        failure = code
        reportError?.invoke()
    }

    DisposableEffect(mediaUrl, source, owner, retry, active, preview, failure) {
        var current: VideoSession? = null
        var observer: LifecycleEventObserver? = null
        var listener: Player.Listener? = null
        if (active && failure == null) {
            try {
                val http = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT)
                    .setDefaultRequestProperties(if (source == Source.INKBUNNY)
                        mapOf("Referer" to "https://inkbunny.net/") else emptyMap())
                val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
                    // Synchronous codec operation avoids device-specific async codec failures.
                    .forceDisableMediaCodecAsynchronousQueueing()
                val player = ExoPlayer.Builder(context.applicationContext, renderers)
                    .setLooper(Looper.getMainLooper())
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
                    .build()
                current = VideoSession(player)
                listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { fail(error.errorCodeName) }
                }
                player.addListener(listener)
                observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) player.pause()
                    if (preview && event == Lifecycle.Event.ON_START) player.play()
                }
                owner.lifecycle.addObserver(observer)
                player.volume = if (preview) 0f else 1f
                player.repeatMode = if (preview) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                player.setMediaItem(MediaItem.fromUri(mediaUrl))
                player.prepare()
                player.playWhenReady = preview && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                session = current
            } catch (error: RuntimeException) {
                Log.w("UnifiedArtVideo", "Video setup failed: ${error.javaClass.simpleName}")
                fail("PLAYER_SETUP_FAILED")
            }
        }
        onDispose {
            observer?.let { owner.lifecycle.removeObserver(it) }
            listener?.let { current?.player?.removeListener(it) }
            current?.release()
        }
    }

    val ready = session
    if (failure != null) {
        if (!preview) Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("This video couldn't play ($failure).")
            TextButton(onClick = { retry++ }) { Text("Retry video") }
        }
    } else if (active && ready != null) {
        key(ready) {
            AndroidView(factory = { viewContext ->
                val container = FrameLayout(viewContext)
                try {
                    val view = createVideoView(viewContext, preview)
                    ready.attach(view)
                    container.addView(view)
                } catch (error: RuntimeException) {
                    Log.w("UnifiedArtVideo", "Video view failed: ${error.javaClass.simpleName}")
                    fail("VIDEO_VIEW_FAILED")
                }
                container
            }, onRelease = { container ->
                (container.getChildAt(0) as? PlayerView)?.let { ready.detach(it) }
                container.removeAllViews()
            }, modifier = Modifier.fillMaxSize())
        }
    } else if (active && !preview) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
