package com.hexnc.music.data.db

import android.content.Context
import com.hexnc.music.data.db.sqlite.SQLiteDatabase
import com.hexnc.music.data.db.sqlite.SQLiteOpenHelper

/**
 * Hand-rolled SQLite schema for the HexNC library.
 *
 * No Room / KSP is used on purpose: the project ships without an annotation processor so the
 * Gradle build stays on a single, well-understood toolchain.
 *
 * Foreign keys are enforced and cascade deletes are wired up, which means **rows must never be
 * written with `INSERT OR REPLACE`** — REPLACE performs a delete + insert and would silently wipe
 * the dependent lyric row and every playlist membership. All upserts in the DAOs go through
 * update-then-insert instead.
 */
class HexNCDatabase(context: Context) :
    SQLiteOpenHelper(context.getDatabasePath(NAME), NAME, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The library is rebuildable from the device + the network, so a destructive upgrade keeps
        // migration code out of the project entirely.
        DROP.forEach(db::execSQL)
        SCHEMA.forEach(db::execSQL)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    companion object {
        const val NAME = "hexnc.db"
        const val VERSION = 1

        const val T_SONGS = "songs"
        const val T_PLAYLISTS = "playlists"
        const val T_PLAYLIST_SONGS = "playlist_songs"
        const val T_LYRICS = "lyrics"
        const val T_META = "meta"

        private val SCHEMA = listOf(
            """
            CREATE TABLE $T_SONGS (
                id TEXT NOT NULL PRIMARY KEY,
                source TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL DEFAULT '',
                album TEXT NOT NULL DEFAULT '',
                album_id INTEGER,
                netease_id INTEGER,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                media_uri TEXT,
                local_path TEXT,
                cover_url TEXT,
                cover_override TEXT,
                track_no INTEGER NOT NULL DEFAULT 0,
                bitrate INTEGER NOT NULL DEFAULT 0,
                size INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL DEFAULT 0,
                favorite INTEGER NOT NULL DEFAULT 0,
                has_lyrics INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_songs_source ON $T_SONGS(source)",
            "CREATE INDEX idx_songs_album ON $T_SONGS(album, artist)",
            "CREATE INDEX idx_songs_added ON $T_SONGS(added_at DESC)",
            "CREATE UNIQUE INDEX idx_songs_netease ON $T_SONGS(netease_id) WHERE netease_id IS NOT NULL",

            """
            CREATE TABLE $T_PLAYLISTS (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                source TEXT NOT NULL,
                netease_id INTEGER,
                description TEXT NOT NULL DEFAULT '',
                creator TEXT NOT NULL DEFAULT '',
                cover_url TEXT,
                cover_override TEXT,
                track_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                synced_at INTEGER NOT NULL DEFAULT 0,
                subscribed INTEGER NOT NULL DEFAULT 0,
                special_type INTEGER NOT NULL DEFAULT 0,
                play_count INTEGER NOT NULL DEFAULT 0,
                position INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_playlists_source ON $T_PLAYLISTS(source)",
            "CREATE UNIQUE INDEX idx_playlists_netease ON $T_PLAYLISTS(netease_id) WHERE netease_id IS NOT NULL",

            // No unique constraint: NetEase playlists legitimately contain the same track twice.
            """
            CREATE TABLE $T_PLAYLIST_SONGS (
                rowid_pk INTEGER PRIMARY KEY AUTOINCREMENT,
                playlist_id TEXT NOT NULL,
                song_id TEXT NOT NULL,
                position INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (playlist_id) REFERENCES $T_PLAYLISTS(id) ON DELETE CASCADE,
                FOREIGN KEY (song_id) REFERENCES $T_SONGS(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE INDEX idx_ps_playlist ON $T_PLAYLIST_SONGS(playlist_id, position)",
            "CREATE INDEX idx_ps_song ON $T_PLAYLIST_SONGS(song_id)",

            """
            CREATE TABLE $T_LYRICS (
                song_id TEXT NOT NULL PRIMARY KEY,
                lrc TEXT,
                tlyric TEXT,
                romalrc TEXT,
                yrc TEXT,
                offset_ms INTEGER NOT NULL DEFAULT 0,
                pure_music INTEGER NOT NULL DEFAULT 0,
                uncollected INTEGER NOT NULL DEFAULT 0,
                origin TEXT NOT NULL DEFAULT 'netease',
                updated_at INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (song_id) REFERENCES $T_SONGS(id) ON DELETE CASCADE
            )
            """.trimIndent(),

            """
            CREATE TABLE $T_META (
                key TEXT NOT NULL PRIMARY KEY,
                value TEXT
            )
            """.trimIndent()
        )

        private val DROP = listOf(
            T_META, T_LYRICS, T_PLAYLIST_SONGS, T_PLAYLISTS, T_SONGS
        ).map { "DROP TABLE IF EXISTS $it" }
    }
}
