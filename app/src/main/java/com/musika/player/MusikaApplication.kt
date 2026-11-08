package com.musika.player

import android.app.Application
import com.musika.player.data.local.MusikaDatabase
import com.musika.player.data.scanner.AudioMetadataParser
import java.io.File

class MusikaApplication : Application() {

    val database: MusikaDatabase by lazy {
        MusikaDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AudioMetadataParser.customArtworkCacheDir = File(cacheDir, "musika_artwork_cache")
    }

    companion object {
        lateinit var instance: MusikaApplication
            private set
    }
}
