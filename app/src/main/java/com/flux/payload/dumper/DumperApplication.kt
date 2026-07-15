package com.flux.payload.dumper

import android.app.Application
import android.content.Context

class DumperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: DumperApplication
            private set
        val appContext: Context get() = instance.applicationContext
    }
}
