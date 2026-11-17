package com.unifiedart.app

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.TextureView
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class VideoPlayerTest {
    private fun context(): Context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.UnifiedArtTheme)

    @Test fun viewerUsesTextureSurfaceAndPlaybackControlsWithAppTheme() {
        val view = createVideoView(context(), preview = false)
        assertTrue(view.videoSurfaceView is TextureView)
        assertTrue(view.useController)
    }

    @Test fun closingViewerDetachesViewBeforeReleasingPlayerAndCanBeRepeated() {
        val context = context()
        val session = VideoSession(ExoPlayer.Builder(context).build())
        val view = createVideoView(context, preview = false)
        try {
            session.attach(view)
            assertSame(session.player, view.player)
            session.release()
            assertNull(view.player)
            session.release()
            session.detach(view) // AndroidView.onRelease can run after effect disposal.
            assertNull(view.player)
        } finally { session.release() }
    }

    @Test fun switchingViewsDetachesOldSurfaceAndReleasedSessionCannotReattach() {
        val context = context()
        val session = VideoSession(ExoPlayer.Builder(context).build())
        val first = createVideoView(context, preview = false)
        val second = createVideoView(context, preview = true)
        try {
            session.attach(first)
            session.attach(second)
            assertNull(first.player)
            assertSame(session.player, second.player)
            assertFalse(second.useController)
            session.detach(second)
            assertNull(second.player)
            session.release()
            assertThrows(IllegalStateException::class.java) { session.attach(first) }
        } finally { session.release() }
    }
}
