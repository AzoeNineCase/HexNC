package com.hexnc.music.di

import android.content.Context
import com.hexnc.music.data.db.HexNCDatabase
import com.hexnc.music.data.db.LyricDao
import com.hexnc.music.data.db.NeteaseAlbumDao
import com.hexnc.music.data.db.PlaylistDao
import com.hexnc.music.data.db.SongDao
import com.hexnc.music.data.download.AndroidDownloadSink
import com.hexnc.music.data.download.DownloadManager
import com.hexnc.music.data.download.SongDownloader
import com.hexnc.music.data.local.CoverStore
import com.hexnc.music.data.local.LocalScanner
import com.hexnc.music.data.lyrics.LrclibClient
import com.hexnc.music.data.netease.NeteaseApi
import com.hexnc.music.data.netease.NeteaseClient
import com.hexnc.music.data.netease.NeteaseCookieStore
import com.hexnc.music.data.netease.NeteaseIdentity
import com.hexnc.music.data.prefs.SettingsStore
import com.hexnc.music.data.repo.LibraryRepository
import com.hexnc.music.data.repo.NeteaseRepository
import com.hexnc.music.data.repo.OnlineLyricsRepository
import com.hexnc.music.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The single object graph for the process.
 *
 * Hand-rolled instead of Hilt/Koin on purpose: the graph is small and flat, and a compile-time DI
 * framework would add an annotation processor to the build for no benefit at this size. Everything
 * takes the application context, so the container can live for the whole process lifetime.
 */
class AppContainer(context: Context) {

    private val app: Context = context.applicationContext

    /** Long-lived scope for work that must outlive any screen (syncs, cover pruning, session warm-up). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- storage ---------------------------------------------------------------
    val database: HexNCDatabase = HexNCDatabase(app)
    val settings: SettingsStore = SettingsStore(app)
    val covers: CoverStore = CoverStore(app)

    private val songDao = SongDao(database)
    private val playlistDao = PlaylistDao(database)
    private val lyricDao = LyricDao(database)
    private val albumDao = NeteaseAlbumDao(database)

    // ---- NetEase ---------------------------------------------------------------
    val cookieStore: NeteaseCookieStore = NeteaseCookieStore(app)
    val identity: NeteaseIdentity = NeteaseIdentity(app)
    private val client = NeteaseClient(cookieStore, identity)
    val api = NeteaseApi(client)

    // ---- repositories ----------------------------------------------------------
    val library: LibraryRepository = LibraryRepository(songDao, playlistDao, lyricDao, covers)
    val netease: NeteaseRepository =
        NeteaseRepository(api, cookieStore, songDao, playlistDao, lyricDao, library, albumDao)

    val scanner: LocalScanner = LocalScanner(app)
    val playback: PlaybackController = PlaybackController(app, netease, settings)

    // ---- downloads ------------------------------------------------------------
    /**
     * Export queue shared by every screen.
     *
     * The manager owns the only running job, so two screens cannot start two transfers, and the sink
     * writes through `MediaStore` on API 29+ so no storage permission is needed.
     */
    val downloads: DownloadManager = DownloadManager(
        scope = scope,
        downloader = SongDownloader(
            netease = netease,
            library = library,
            sink = AndroidDownloadSink(app),
        ),
    )

    // ---- online lyric completion -------------------------------------------------
    /** The key-less second lyric source; no session, so it is built eagerly and owned here. */
    val lrclib: LrclibClient = LrclibClient()
    val online: OnlineLyricsRepository = OnlineLyricsRepository(netease, lrclib, library)

    /**
     * Restores a persisted NetEase session and binds playback at startup.
     *
     * Both are fire-and-forget: the UI renders signed-out / inert states until they resolve, which
     * is what a cold start on a plane looks like anyway.
     */
    fun warmUp() {
        playback.connect()
        scope.launch { netease.refreshSession() }
    }

    fun shutdown() {
        playback.release()
        settings.close()
    }
}
