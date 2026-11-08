package com.musika.player

import android.app.Application
import com.musika.player.data.local.MusikaDatabase

class MusikaApplication : Application() {

    val database: MusikaDatabase by lazy {
        MusikaDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: MusikaApplication
            private set
    }
}
