package com.hexnc.music

import android.content.Context
import com.hexnc.music.di.AppContainer
import java.io.File

/**
 * Desktop counterpart of the Android `HexNCApp : Application`.
 *
 * It is an [`android.content.Context`] so that the shared data layer (repositories, DAOs, the
 * preference stores, the cover store) can be constructed exactly as it is on Android, and it exposes
 * the same `container` property the UI reaches through `LocalContext.current`.
 */
class HexNCApp(baseDir: File) : Context(baseDir) {

    private val containerDelegate = lazy { AppContainer(this) }

    /** Built on first use so a slow disk does not delay showing the window. */
    val container: AppContainer by containerDelegate

    /** Where the user's data lives; also what "open data folder" in Settings points at. */
    val dataDir: File get() = filesDir

    /** Releases the player and closes the preference store, but only if they were ever created. */
    fun shutdown() {
        if (containerDelegate.isInitialized()) container.shutdown()
    }

    companion object {
        /** `%APPDATA%\HexNC`, or `~/.hexnc` when `APPDATA` is missing. */
        fun defaultDataDir(): File {
            val appData = System.getenv("APPDATA")
            val home = System.getProperty("user.home").orEmpty()
            val base = if (!appData.isNullOrBlank()) File(appData) else File(home)
            return File(base, "HexNC")
        }
    }
}
