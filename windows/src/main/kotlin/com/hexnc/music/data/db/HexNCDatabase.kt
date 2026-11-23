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

    /**
     * Additive migration only.
     *
     * The old body dropped and recreated every table, so any schema change silently threw away
     * favourites, cover overrides and downloaded lyrics — and "clear the data and sign in again" is
     * exactly the workaround this release is removing. New tables are created in place and existing
     * rows are left alone. Anything genuinely un-migratable can still fall back to [rebuild].
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < V2) NC_ALBUM_SCHEMA.forEach(db::execSQL)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // An older build against a newer database has no forward-compatible reading path, so the
        // library is rebuilt from scratch rather than left half-readable.
        rebuild(db)
    }

    private fun rebuild(db: SQLiteDatabase) {
        DROP.forEach(db::execSQL)
        SCHEMA.forEach(db::execSQL)
    }

    companion object {
        const val NAME = "hexnc.db"

        /** 1: original library. 2: favourited-album cache ([T_NC_ALBUMS]). */
        const val VERSION = 2
        private const val V2 = 2

        const val T_SONGS = "songs"
        const val T_PLAYLISTS = "playlists"
        const val T_PLAYLIST_SONGS = "playlist_songs"
        const val T_LYRICS = "lyrics"
        const val T_META = "meta"
        const val T_NC_ALBUMS = "nc_albums"

        /** The favourited-album cache; kept apart so it can be added without touching v1 tables. */
        private val NC_ALBUM_SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS $T_NC_ALBUMS (
                id INTEGER NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                artist TEXT NOT NULL DEFAULT '',
                cover_url TEXT,
                track_count INTEGER NOT NULL DEFAULT 0,
                publish_time INTEGER NOT NULL DEFAULT 0,
                position INTEGER NOT NULL DEFAULT 0,
                synced_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_nc_albums_position ON $T_NC_ALBUMS(position ASC)",
        )

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
            """.trimIndent(),
        ) + NC_ALBUM_SCHEMA

        private val DROP = listOf(
            T_NC_ALBUMS, T_META, T_LYRICS, T_PLAYLIST_SONGS, T_PLAYLISTS, T_SONGS
        ).map { "DROP TABLE IF EXISTS $it" }
    }
}
