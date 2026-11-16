package com.unifiedart.app

import android.app.Application
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

class UnifiedArtApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.unifiedart.app.data.FollowingWorker.schedule(this)
        Coil.setImageLoader(ImageLoader.Builder(this).components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build())
    }
}
