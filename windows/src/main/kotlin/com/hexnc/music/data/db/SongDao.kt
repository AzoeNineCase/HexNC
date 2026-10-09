package com.hexnc.music.data.db

import com.hexnc.music.data.db.sqlite.ContentValues
import com.hexnc.music.data.db.sqlite.Cursor
import com.hexnc.music.data.db.sqlite.SQLiteDatabase
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.model.SongSource

/** Sort orders offered by the library list; the SQL is fixed so no user string reaches the query. */
enum class SongOrder(val sql: String) {
    ADDED_DESC("added_at DESC, title COLLATE NOCASE ASC"),
    ADDED_ASC("added_at ASC, title COLLATE NOCASE ASC"),
    TITLE_ASC("title COLLATE NOCASE ASC"),
    ARTIST_ASC("artist COLLATE NOCASE ASC, album COLLATE NOCASE ASC, track_no ASC"),
    ALBUM_ASC("album COLLATE NOCASE ASC, track_no ASC, title COLLATE NOCASE ASC"),
    DURATION_ASC("duration_ms ASC")
}

/** One album as derived from the song table (there is no separate album table). */
data class AlbumGroup(
    val album: String,
    val artist: String,
    val coverUrl: String?,
    val coverOverridePath: String?,
    val trackCount: Int,
    val durationMs: Long
)

/** One artist as derived from the song table. */
data class ArtistGroup(
    val artist: String,
    val trackCount: Int,
    val albumCount: Int
)

class SongDao(private val helper: HexNCDatabase) {

    /**
     * Insert or update a song without ever issuing `INSERT OR REPLACE`, so that dependent lyrics and
     * playlist memberships survive a re-sync.
     *
     * @return true when an existing row was updated.
     */
    fun upsert(song: Song): Boolean {
        val db = helper.writableDatabase
        return db.tx { upsertLocked(db, song) }
    }

    /** Bulk upsert. Returns the number of rows that were newly inserted. */
    fun upsertAll(songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        val db = helper.writableDatabase
        return db.tx {
            var inserted = 0
            songs.forEach { if (!upsertLocked(db, it)) inserted++ }
            inserted
        }
    }

    /**
     * Inserts a song, or refreshes its *catalogue* fields if the row already exists.
     *
     * The split matters: a NetEase re-sync and a local re-scan both know the title, artist, album
     * and duration, but neither knows whether the user starred the track, attached a cover, or
     * imported a `.lrc`. Those three belong to the device, so they are written only on insert and
     * are deliberately absent from the update, which is why this cannot be a plain `ContentValues`
     * update of the whole row.
     *
     * @return true when an existing row was updated.
     */
    private fun upsertLocked(db: SQLiteDatabase, song: Song): Boolean {
        val updated = db.compileStatement(
            """
            UPDATE ${HexNCDatabase.T_SONGS}
               SET source = ?, title = ?, artist = ?, album = ?, album_id = ?, netease_id = ?,
                   duration_ms = ?, media_uri = ?, local_path = ?, cover_url = ?,
                   track_no = ?, bitrate = ?, size = ?
             WHERE id = ?
            """.trimIndent()
        ).use { st ->
            st.bindString(1, song.source.name)
            st.bindString(2, song.title)
            st.bindString(3, song.artist)
            st.bindString(4, song.album)
            if (song.albumId == null) st.bindNull(5) else st.bindLong(5, song.albumId)
            if (song.neteaseId == null) st.bindNull(6) else st.bindLong(6, song.neteaseId)
            st.bindLong(7, song.durationMs)
            if (song.mediaUri == null) st.bindNull(8) else st.bindString(8, song.mediaUri)
            if (song.localPath == null) st.bindNull(9) else st.bindString(9, song.localPath)
            if (song.coverUrl == null) st.bindNull(10) else st.bindString(10, song.coverUrl)
            st.bindLong(11, song.trackNo.toLong())
            st.bindLong(12, song.bitrate.toLong())
            st.bindLong(13, song.size)
            st.bindString(14, song.id)
            st.executeUpdateDelete().toLong()
        }
        if (updated == 0L) {
            db.insertWithOnConflict(
                HexNCDatabase.T_SONGS, null, song.toValues(), SQLiteDatabase.CONFLICT_IGNORE
            )
        }
        return updated > 0L
    }

    fun byId(id: String): Song? =
        query("SELECT * FROM ${HexNCDatabase.T_SONGS} WHERE id = ?", arrayOf(id)).firstOrNull()

    fun byLocalPath(path: String): Song? =
        query(
            "SELECT * FROM ${HexNCDatabase.T_SONGS} WHERE local_path = ? LIMIT 1",
            arrayOf(path)
        ).firstOrNull()

    fun byNeteaseId(id: Long): Song? =
        query(
            "SELECT * FROM ${HexNCDatabase.T_SONGS} WHERE netease_id = ? LIMIT 1",
            arrayOf(id.toString())
        ).firstOrNull()

    /** Resolve many NetEase ids at once, chunked to stay under SQLite's variable limit. */
    fun byNeteaseIds(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Song>(ids.size)
        ids.distinct().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            out += query(
                "SELECT * FROM ${HexNCDatabase.T_SONGS} WHERE netease_id IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray()
            )
        }
        return out
    }

    fun byIds(ids: List<String>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Song>(ids.size)
        ids.distinct().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            out += query(
                "SELECT * FROM ${HexNCDatabase.T_SONGS} WHERE id IN ($placeholders)",
                chunk.toTypedArray()
            )
        }
        return out
    }

    fun all(order: SongOrder = SongOrder.TITLE_ASC, limit: Int = 5000, offset: Int = 0): List<Song> =
        query(
            "SELECT * FROM ${HexNCDatabase.T_SONGS} ORDER BY ${order.sql} LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString())
        )

    fun bySource(source: SongSource, order: SongOrder = SongOrder.TITLE_ASC): List<Song> =
        query(
            "SELECT * FROM ${HexNCDatabase.T_SONGS} WHERE source = ? ORDER BY ${order.sql}",
            arrayOf(source.name)
        )

    fun favorites(order: SongOrder = SongOrder.ADDED_DESC): List<Song> =
        query(
            "SELECT * FROM ${HexNCDatabase.T_SONGS} WHERE favorite = 1 ORDER BY ${order.sql}"
        )

    fun recentlyAdded(limit: Int = 50): List<Song> =
        query(
            "SELECT * FROM ${HexNCDatabase.T_SONGS} ORDER BY added_at DESC LIMIT ?",
            arrayOf(limit.toString())
        )

    /** Free-text search across title / artist / album. */
    fun search(text: String, limit: Int = 60): List<Song> {
        val q = text.trim()
        if (q.isEmpty()) return emptyList()
        val like = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%"
        return query(
            """
            SELECT * FROM ${HexNCDatabase.T_SONGS}
            WHERE title LIKE ? ESCAPE '\' OR artist LIKE ? ESCAPE '\' OR album LIKE ? ESCAPE '\'
            ORDER BY
                CASE WHEN title LIKE ? ESCAPE '\' THEN 0 ELSE 1 END,
                title COLLATE NOCASE ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(like, like, like, like, limit.toString())
        )
    }

    fun albums(): List<AlbumGroup> {
        val sql = """
            SELECT album,
                   MIN(artist) AS artist,
                   MIN(cover_url) AS cover_url,
                   MIN(cover_override) AS cover_override,
                   COUNT(*) AS track_count,
                   SUM(duration_ms) AS total_ms
            FROM ${HexNCDatabase.T_SONGS}
            WHERE album <> ''
            GROUP BY album
            ORDER BY album COLLATE NOCASE ASC
        """.trimIndent()
        val out = ArrayList<AlbumGroup>()
        helper.readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out += AlbumGroup(
                    album = c.str("album"),
                    artist = c.str("artist"),
                    coverUrl = c.strOrNull("cover_url"),
                    coverOverridePath = c.strOrNull("cover_override"),
                    trackCount = c.int("track_count"),
                    durationMs = c.long("total_ms")
                )
            }
        }
        return out
    }

    fun albumSongs(album: String): List<Song> =
        query(
            """
            SELECT * FROM ${HexNCDatabase.T_SONGS}
            WHERE album = ? ORDER BY track_no ASC, title COLLATE NOCASE ASC
            """.trimIndent(),
            arrayOf(album)
        )

    fun artists(): List<ArtistGroup> {
        val sql = """
            SELECT artist, COUNT(*) AS track_count, COUNT(DISTINCT album) AS album_count
            FROM ${HexNCDatabase.T_SONGS}
            WHERE artist <> ''
            GROUP BY artist
            ORDER BY artist COLLATE NOCASE ASC
        """.trimIndent()
        val out = ArrayList<ArtistGroup>()
        helper.readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out += ArtistGroup(
                    artist = c.str("artist"),
                    trackCount = c.int("track_count"),
                    albumCount = c.int("album_count")
                )
            }
        }
        return out
    }

    fun artistSongs(artist: String): List<Song> =
        query(
            """
            SELECT * FROM ${HexNCDatabase.T_SONGS}
            WHERE artist = ? ORDER BY album COLLATE NOCASE ASC, track_no ASC
            """.trimIndent(),
            arrayOf(artist)
        )

    fun setFavorite(id: String, value: Boolean) {
        helper.writableDatabase.execSQL(
            "UPDATE ${HexNCDatabase.T_SONGS} SET favorite = ? WHERE id = ?",
            sqlArgs(if (value) 1 else 0, id)
        )
    }

    fun setHasLyrics(id: String, value: Boolean) {
        helper.writableDatabase.execSQL(
            "UPDATE ${HexNCDatabase.T_SONGS} SET has_lyrics = ? WHERE id = ?",
            sqlArgs(if (value) 1 else 0, id)
        )
    }

    /** Override the artwork for a single track (or, when [album] is set, for a whole album). */
    fun setCoverOverride(id: String, coverPath: String?) {
        helper.writableDatabase.execSQL(
            "UPDATE ${HexNCDatabase.T_SONGS} SET cover_override = ? WHERE id = ?",
            arrayOf(coverPath, id)
        )
    }

    /**
     * Applies (or clears) one cover across every track on [album].
     *
     * @param coverPath a path inside the app's cover store, or null to drop the override.
     */
    fun setAlbumCoverOverride(album: String, coverPath: String?): Int =
        helper.writableDatabase.compileStatement(
            "UPDATE ${HexNCDatabase.T_SONGS} SET cover_override = ? WHERE album = ?"
        ).use { st ->
            if (coverPath == null) st.bindNull(1) else st.bindString(1, coverPath)
            st.bindString(2, album)
            st.executeUpdateDelete()
        }

    fun setMediaUri(id: String, uri: String?) {
        helper.writableDatabase.execSQL(
            "UPDATE ${HexNCDatabase.T_SONGS} SET media_uri = ? WHERE id = ?",
            arrayOf(uri, id)
        )
    }

    /** Deletes one song. @return the number of rows removed, so callers can report an exact count. */
    fun delete(id: String): Int =
        helper.writableDatabase.delete(HexNCDatabase.T_SONGS, "id = ?", arrayOf(id))

    fun deleteMany(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val db = helper.writableDatabase
        return db.tx {
            var n = 0
            ids.chunked(400).forEach { chunk ->
                n += db.delete(
                    HexNCDatabase.T_SONGS,
                    "id IN (${chunk.joinToString(",") { "?" }})",
                    chunk.toTypedArray()
                )
            }
            n
        }
    }

    fun count(source: SongSource? = null): Int {
        val db = helper.readableDatabase
        return if (source == null) {
            db.scalarLong("SELECT COUNT(*) FROM ${HexNCDatabase.T_SONGS}")?.toInt() ?: 0
        } else {
            db.scalarLong(
                "SELECT COUNT(*) FROM ${HexNCDatabase.T_SONGS} WHERE source = ?", source.name
            )?.toInt() ?: 0
        }
    }

    fun totalDurationMs(source: SongSource? = null): Long {
        val db = helper.readableDatabase
        return if (source == null) {
            db.scalarLong("SELECT SUM(duration_ms) FROM ${HexNCDatabase.T_SONGS}") ?: 0L
        } else {
            db.scalarLong(
                "SELECT SUM(duration_ms) FROM ${HexNCDatabase.T_SONGS} WHERE source = ?",
                source.name
            ) ?: 0L
        }
    }

    fun query(sql: String, args: Array<String>? = null): List<Song> {
        val out = ArrayList<Song>()
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += c.toSong()
        }
        return out
    }

    private fun Cursor.toSong(): Song = Song(
        id = str("id"),
        source = SongSource.fromKey(strOrNull("source")),
        title = str("title"),
        artist = str("artist"),
        album = str("album"),
        albumId = longOrNull("album_id"),
        neteaseId = longOrNull("netease_id"),
        durationMs = long("duration_ms"),
        mediaUri = strOrNull("media_uri"),
        localPath = strOrNull("local_path"),
        coverUrl = strOrNull("cover_url"),
        coverOverridePath = strOrNull("cover_override"),
        trackNo = int("track_no"),
        bitrate = int("bitrate"),
        size = long("size"),
        addedAt = long("added_at"),
        favorite = bool("favorite"),
        hasLyrics = bool("has_lyrics")
    )

    private fun Song.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("source", source.name)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("album_id", albumId)
        put("netease_id", neteaseId)
        put("duration_ms", durationMs)
        put("media_uri", mediaUri)
        put("local_path", localPath)
        put("cover_url", coverUrl)
        put("cover_override", coverOverridePath)
        put("track_no", trackNo)
        put("bitrate", bitrate)
        put("size", size)
        put("added_at", if (addedAt > 0) addedAt else System.currentTimeMillis())
        put("favorite", if (favorite) 1 else 0)
        put("has_lyrics", if (hasLyrics) 1 else 0)
    }
}
