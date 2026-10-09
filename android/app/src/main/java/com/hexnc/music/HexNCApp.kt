package com.hexnc.music

import android.app.Application
import com.hexnc.music.di.AppContainer

/**
 * Owns the process-wide [AppContainer].
 *
 * `Application` is the only component guaranteed to exist before any activity, so building the
 * graph here keeps every screen free of ownership questions — screens read `app.container` and
 * never construct a repository themselves.
 */
class HexNCApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
    }

    override fun onTerminate() {
        container.shutdown()
        super.onTerminate()
    }
}
