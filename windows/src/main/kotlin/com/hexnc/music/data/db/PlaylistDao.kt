package com.hexnc.music.data.db

import com.hexnc.music.data.db.sqlite.ContentValues
import com.hexnc.music.data.db.sqlite.Cursor
import com.hexnc.music.data.db.sqlite.SQLiteDatabase
import com.hexnc.music.data.model.Playlist
import com.hexnc.music.data.model.PlaylistSource
import com.hexnc.music.data.model.Song

class PlaylistDao(private val helper: HexNCDatabase) {

    fun upsert(playlist: Playlist): Boolean {
        val db = helper.writableDatabase
        return db.tx { upsertLocked(db, playlist) }
    }

    fun upsertAll(list: List<Playlist>): Int {
        if (list.isEmpty()) return 0
        val db = helper.writableDatabase
        return db.tx {
            var inserted = 0
            list.forEach { if (!upsertLocked(db, it)) inserted++ }
            inserted
        }
    }

    /**
     * Inserts a playlist, or refreshes its *catalogue* fields if the row already exists.
     *
     * `cover_override`, `created_at` and `position` are omitted from the update so that a re-sync
     * cannot discard a user-chosen cover, reset when the playlist was added, or undo a manual
     * reordering — none of which the server knows anything about.
     *
     * @return true when an existing row was updated.
     */
    private fun upsertLocked(db: SQLiteDatabase, playlist: Playlist): Boolean {
        val updated = db.compileStatement(
            """
            UPDATE ${HexNCDatabase.T_PLAYLISTS}
               SET name = ?, source = ?, netease_id = ?, description = ?, creator = ?,
                   cover_url = ?, track_count = ?, updated_at = ?, synced_at = ?,
                   subscribed = ?, special_type = ?, play_count = ?
             WHERE id = ?
            """.trimIndent()
        ).use { st ->
            st.bindString(1, playlist.name)
            st.bindString(2, playlist.source.name)
            if (playlist.neteaseId == null) st.bindNull(3) else st.bindLong(3, playlist.neteaseId)
            st.bindString(4, playlist.description)
            st.bindString(5, playlist.creator)
            if (playlist.coverUrl == null) st.bindNull(6) else st.bindString(6, playlist.coverUrl)
            st.bindLong(7, playlist.trackCount.toLong())
            st.bindLong(8, if (playlist.updatedAt > 0) playlist.updatedAt else System.currentTimeMillis())
            st.bindLong(9, playlist.syncedAt)
            st.bindLong(10, if (playlist.subscribed) 1 else 0)
            st.bindLong(11, playlist.specialType.toLong())
            st.bindLong(12, playlist.playCount)
            st.bindString(13, playlist.id)
            st.executeUpdateDelete().toLong()
        }
        if (updated == 0L) {
            db.insertWithOnConflict(
                HexNCDatabase.T_PLAYLISTS, null, playlist.toValues(), SQLiteDatabase.CONFLICT_IGNORE
            )
        }
        return updated > 0L
    }

    fun byId(id: String): Playlist? =
        query("SELECT * FROM ${HexNCDatabase.T_PLAYLISTS} WHERE id = ?", arrayOf(id)).firstOrNull()

    fun byNeteaseId(neteaseId: Long): Playlist? =
        query(
            "SELECT * FROM ${HexNCDatabase.T_PLAYLISTS} WHERE netease_id = ? LIMIT 1",
            arrayOf(neteaseId.toString())
        ).firstOrNull()

    fun all(): List<Playlist> =
        query(
            """
            SELECT * FROM ${HexNCDatabase.T_PLAYLISTS}
            ORDER BY position ASC, name COLLATE NOCASE ASC
            """.trimIndent()
        )

    fun bySource(source: PlaylistSource): List<Playlist> =
        query(
            """
            SELECT * FROM ${HexNCDatabase.T_PLAYLISTS}
            WHERE source = ? ORDER BY position ASC, name COLLATE NOCASE ASC
            """.trimIndent(),
            arrayOf(source.name)
        )

    fun neteaseIds(): List<Long> {
        val out = ArrayList<Long>()
        helper.readableDatabase.rawQuery(
            "SELECT netease_id FROM ${HexNCDatabase.T_PLAYLISTS} WHERE netease_id IS NOT NULL",
            null
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    fun neteaseIdsIn(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val found = HashSet<Long>(ids.size)
        val out = ArrayList<Long>()
        ids.distinct().chunked(400).forEach { chunk ->
            val ph = chunk.joinToString(",") { "?" }
            helper.readableDatabase.rawQuery(
                "SELECT netease_id FROM ${HexNCDatabase.T_PLAYLISTS} WHERE netease_id IN ($ph)",
                chunk.map { it.toString() }.toTypedArray()
            ).use { c -> while (c.moveToNext()) found += c.getLong(0) }
        }
        ids.forEach { if (it in found) out += it }
        return out
    }

    fun count(source: PlaylistSource? = null): Int {
        val db = helper.readableDatabase
        return if (source == null) {
            db.scalarLong("SELECT COUNT(*) FROM ${HexNCDatabase.T_PLAYLISTS}")?.toInt() ?: 0
        } else {
            db.scalarLong(
                "SELECT COUNT(*) FROM ${HexNCDatabase.T_PLAYLISTS} WHERE source = ?", source.name
            )?.toInt() ?: 0
        }
    }

    /** Delete local playlists (never NetEase ones, which are owned by the server). */
    fun delete(id: String): Int =
        helper.writableDatabase.delete(HexNCDatabase.T_PLAYLISTS, "id = ?", arrayOf(id))

    /**
     * Remove NetEase playlists that are no longer present on the account.
     * Returns the ids that were dropped. Only playlists whose id is in [candidateIds] are eligible,
     * so local playlists and not-yet-synced NetEase playlists are never touched.
     */
    fun deleteNeteaseNotIn(candidateIds: List<Long>, keep: Collection<Long>): List<Long> {
        if (candidateIds.isEmpty()) return emptyList()
        val doomed = candidateIds.filterNot { it in keep }
        if (doomed.isEmpty()) return emptyList()
        val db = helper.writableDatabase
        db.tx {
            doomed.chunked(400).forEach { chunk ->
                db.delete(
                    HexNCDatabase.T_PLAYLISTS,
                    "source = ? AND netease_id IN (${chunk.joinToString(",") { "?" }})",
                    (listOf(PlaylistSource.NETEASE.name) + chunk.map { it.toString() }).toTypedArray()
                )
            }
        }
        return doomed
    }

    fun setCoverOverride(id: String, coverPath: String?) {
        helper.writableDatabase.execSQL(
            "UPDATE ${HexNCDatabase.T_PLAYLISTS} SET cover_override = ? WHERE id = ?",
            arrayOf(coverPath, id)
        )
    }

    fun setSyncedAt(id: String, at: Long) {
        helper.writableDatabase.execSQL(
            "UPDATE ${HexNCDatabase.T_PLAYLISTS} SET synced_at = ? WHERE id = ?",
            sqlArgs(at, id)
        )
    }

    fun rename(id: String, name: String) {
        helper.writableDatabase.execSQL(
            "UPDATE ${HexNCDatabase.T_PLAYLISTS} SET name = ?, updated_at = ? WHERE id = ?",
            sqlArgs(name, System.currentTimeMillis(), id)
        )
    }

    fun setPosition(id: String, position: Int) {
        helper.writableDatabase.execSQL(
            "UPDATE ${HexNCDatabase.T_PLAYLISTS} SET position = ? WHERE id = ?",
            sqlArgs(position, id)
        )
    }

    // ---------------------------------------------------------------- membership

    fun songIds(playlistId: String): List<String> {
        val out = ArrayList<String>()
        helper.readableDatabase.rawQuery(
            """
            SELECT song_id FROM ${HexNCDatabase.T_PLAYLIST_SONGS}
            WHERE playlist_id = ? ORDER BY position ASC, rowid_pk ASC
            """.trimIndent(),
            arrayOf(playlistId)
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun neteaseIdsInPlaylist(playlistId: String): List<Long> {
        val out = ArrayList<Long>()
        helper.readableDatabase.rawQuery(
            """
            SELECT s.netease_id FROM ${HexNCDatabase.T_PLAYLIST_SONGS} ps
            JOIN ${HexNCDatabase.T_SONGS} s ON s.id = ps.song_id
            WHERE ps.playlist_id = ? AND s.netease_id IS NOT NULL
            ORDER BY ps.position ASC
            """.trimIndent(),
            arrayOf(playlistId)
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    fun songs(playlistId: String): List<Song> {
        val out = ArrayList<Song>()
        helper.readableDatabase.rawQuery(
            """
            SELECT s.* FROM ${HexNCDatabase.T_PLAYLIST_SONGS} ps
            JOIN ${HexNCDatabase.T_SONGS} s ON s.id = ps.song_id
            WHERE ps.playlist_id = ?
            ORDER BY ps.position ASC, ps.rowid_pk ASC
            """.trimIndent(),
            arrayOf(playlistId)
        ).use { c -> while (c.moveToNext()) out += c.toSongRow() }
        return out
    }

    /** Sorted view of the same membership, for the "sort playlist" affordance. */
    fun songsSorted(playlistId: String, order: SongOrder): List<Song> {
        val out = ArrayList<Song>()
        helper.readableDatabase.rawQuery(
            """
            SELECT s.* FROM ${HexNCDatabase.T_PLAYLIST_SONGS} ps
            JOIN ${HexNCDatabase.T_SONGS} s ON s.id = ps.song_id
            WHERE ps.playlist_id = ?
            ORDER BY ${order.sql}
            """.trimIndent(),
            arrayOf(playlistId)
        ).use { c -> while (c.moveToNext()) out += c.toSongRow() }
        return out
    }

    /**
     * Append a single song to the end of a playlist.
     * @param allowDuplicate when false the insert is skipped if the song is already a member.
     */
    fun addSong(playlistId: String, songId: String, allowDuplicate: Boolean = false): Boolean {
        val db = helper.writableDatabase
        return db.tx {
            if (!allowDuplicate && songIds(playlistId).contains(songId)) {
                false
            } else {
                val position = (db.scalarLong(
                    "SELECT MAX(position) FROM ${HexNCDatabase.T_PLAYLIST_SONGS} WHERE playlist_id = ?",
                    playlistId
                ) ?: -1L) + 1L
                val values = ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("song_id", songId)
                    put("position", position)
                    put("added_at", System.currentTimeMillis())
                }
                val ok = db.insertWithOnConflict(
                    HexNCDatabase.T_PLAYLIST_SONGS, null, values, SQLiteDatabase.CONFLICT_IGNORE
                ) >= 0
                if (ok) refreshTrackCount(db, playlistId)
                ok
            }
        }
    }

    fun addSongs(playlistId: String, songIds: List<String>, allowDuplicate: Boolean = true): Int {
        if (songIds.isEmpty()) return 0
        val db = helper.writableDatabase
        return db.tx {
            var position = (db.scalarLong(
                "SELECT MAX(position) FROM ${HexNCDatabase.T_PLAYLIST_SONGS} WHERE playlist_id = ?",
                playlistId
            ) ?: -1L) + 1L
            val existing = if (allowDuplicate) emptySet() else songIds(playlistId).toSet()
            var added = 0
            songIds.forEach { sid ->
                if (sid in existing) return@forEach
                val values = ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("song_id", sid)
                    put("position", position)
                    put("added_at", System.currentTimeMillis())
                }
                val row = db.insertWithOnConflict(
                    HexNCDatabase.T_PLAYLIST_SONGS, null, values, SQLiteDatabase.CONFLICT_IGNORE
                )
                if (row >= 0) {
                    added++
                    position++
                }
            }
            if (added > 0) refreshTrackCount(db, playlistId)
            added
        }
    }

    /** Remove every occurrence of [songId] from [playlistId]. Returns rows deleted. */
    fun removeSong(playlistId: String, songId: String): Int {
        val db = helper.writableDatabase
        return db.tx {
            val n = db.delete(
                HexNCDatabase.T_PLAYLIST_SONGS,
                "playlist_id = ? AND song_id = ?",
                arrayOf(playlistId, songId)
            )
            if (n > 0) {
                renumber(db, playlistId)
                refreshTrackCount(db, playlistId)
            }
            n
        }
    }

    fun removeSongEverywhere(songId: String): Int =
        helper.writableDatabase.delete(
            HexNCDatabase.T_PLAYLIST_SONGS, "song_id = ?", arrayOf(songId)
        )

    fun moveSong(playlistId: String, from: Int, to: Int): Boolean {
        if (from == to) return false
        val ids = songIds(playlistId).toMutableList()
        if (from !in ids.indices || to !in ids.indices) return false
        val moved = ids.removeAt(from)
        ids.add(to, moved)
        replaceMembers(playlistId, ids)
        return true
    }

    /**
     * Rewrite the whole membership list of [playlistId] in one transaction.
     * Used by NetEase sync, where the server ordering is authoritative.
     */
    fun replaceMembers(playlistId: String, songIds: List<String>) {
        val db = helper.writableDatabase
        db.tx {
            db.delete(HexNCDatabase.T_PLAYLIST_SONGS, "playlist_id = ?", arrayOf(playlistId))
            insertMembers(db, playlistId, songIds)
            refreshTrackCount(db, playlistId)
        }
    }

    /**
     * Add tracks that are missing and drop membership rows whose song no longer exists locally,
     * preserving the server order for the entries that are already there.
     */
    fun reconcileMembers(playlistId: String, songIds: List<String>) {
        val db = helper.writableDatabase
        db.tx {
            val current = songIds(playlistId)
            val valid = HashSet<String>(songIds.size)
            songIds.forEach { valid += it }
            val stale = current.filterNot { it in valid }
            stale.forEach { sid ->
                db.delete(
                    HexNCDatabase.T_PLAYLIST_SONGS,
                    "playlist_id = ? AND song_id = ?",
                    arrayOf(playlistId, sid)
                )
            }
            // Drop rows pointing at songs that no longer exist (FK would already prevent this, but
            // a partially-imported playlist can still reference a pruned id).
            val present = HashSet<String>()
            db.rawQuery(
                "SELECT song_id FROM ${HexNCDatabase.T_PLAYLIST_SONGS} WHERE playlist_id = ?",
                arrayOf(playlistId)
            ).use { c -> while (c.moveToNext()) present += c.getString(0) }
            val missing = songIds.filterNot { it in present }
            var position = (db.scalarLong(
                "SELECT MAX(position) FROM ${HexNCDatabase.T_PLAYLIST_SONGS} WHERE playlist_id = ?",
                playlistId
            ) ?: -1L) + 1L
            missing.forEach { sid ->
                val values = ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("song_id", sid)
                    put("position", position)
                    put("added_at", System.currentTimeMillis())
                }
                if (db.insertWithOnConflict(
                        HexNCDatabase.T_PLAYLIST_SONGS, null, values, SQLiteDatabase.CONFLICT_IGNORE
                    ) >= 0
                ) position++
            }
            if (stale.isNotEmpty() || missing.isNotEmpty()) renumber(db, playlistId)
            refreshTrackCount(db, playlistId)
        }
    }

    fun size(playlistId: String): Int =
        helper.readableDatabase.scalarLong(
            "SELECT COUNT(*) FROM ${HexNCDatabase.T_PLAYLIST_SONGS} WHERE playlist_id = ?",
            playlistId
        )?.toInt() ?: 0

    fun playlistsContaining(songId: String): List<Playlist> =
        query(
            """
            SELECT p.* FROM ${HexNCDatabase.T_PLAYLIST_SONGS} ps
            JOIN ${HexNCDatabase.T_PLAYLISTS} p ON p.id = ps.playlist_id
            WHERE ps.song_id = ?
            ORDER BY p.position ASC, p.name COLLATE NOCASE ASC
            """.trimIndent(),
            arrayOf(songId)
        )

    // ---------------------------------------------------------------- internals

    private fun insertMembers(db: SQLiteDatabase, playlistId: String, songIds: List<String>) {
        val statement = db.compileStatement(
            """
            INSERT INTO ${HexNCDatabase.T_PLAYLIST_SONGS} (playlist_id, song_id, position, added_at)
            VALUES (?, ?, ?, ?)
            """.trimIndent()
        )
        statement.use { st ->
            val now = System.currentTimeMillis()
            songIds.forEachIndexed { index, sid ->
                st.clearBindings()
                st.bindString(1, playlistId)
                st.bindString(2, sid)
                st.bindLong(3, index.toLong())
                st.bindLong(4, now)
                st.executeInsert()
            }
        }
    }

    private fun renumber(db: SQLiteDatabase, playlistId: String) {
        val ids = ArrayList<String>()
        db.rawQuery(
            """
            SELECT song_id FROM ${HexNCDatabase.T_PLAYLIST_SONGS}
            WHERE playlist_id = ? ORDER BY position ASC, rowid_pk ASC
            """.trimIndent(),
            arrayOf(playlistId)
        ).use { c -> while (c.moveToNext()) ids += c.getString(0) }
        db.delete(HexNCDatabase.T_PLAYLIST_SONGS, "playlist_id = ?", arrayOf(playlistId))
        insertMembers(db, playlistId, ids)
    }

    private fun refreshTrackCount(db: SQLiteDatabase, playlistId: String) {
        db.execSQL(
            """
            UPDATE ${HexNCDatabase.T_PLAYLISTS}
            SET track_count = (SELECT COUNT(*) FROM ${HexNCDatabase.T_PLAYLIST_SONGS} WHERE playlist_id = ?),
                updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            sqlArgs(playlistId, System.currentTimeMillis(), playlistId)
        )
    }

    fun query(sql: String, args: Array<String>? = null): List<Playlist> {
        val out = ArrayList<Playlist>()
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += c.toPlaylist()
        }
        return out
    }

    private fun Cursor.toPlaylist(): Playlist = Playlist(
        id = str("id"),
        name = str("name"),
        source = PlaylistSource.fromKey(strOrNull("source")),
        neteaseId = longOrNull("netease_id"),
        description = str("description"),
        creator = str("creator"),
        coverUrl = strOrNull("cover_url"),
        coverOverridePath = strOrNull("cover_override"),
        trackCount = int("track_count"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
        syncedAt = long("synced_at"),
        subscribed = bool("subscribed"),
        specialType = int("special_type"),
        playCount = long("play_count")
    )

    private fun Playlist.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("name", name)
        put("source", source.name)
        put("netease_id", neteaseId)
        put("description", description)
        put("creator", creator)
        put("cover_url", coverUrl)
        put("cover_override", coverOverridePath)
        put("track_count", trackCount)
        put("created_at", if (createdAt > 0) createdAt else System.currentTimeMillis())
        put("updated_at", if (updatedAt > 0) updatedAt else System.currentTimeMillis())
        put("synced_at", syncedAt)
        put("subscribed", if (subscribed) 1 else 0)
        put("special_type", specialType)
        put("play_count", playCount)
    }

    private fun Cursor.toSongRow(): Song = Song(
        id = str("id"),
        source = com.hexnc.music.data.model.SongSource.fromKey(strOrNull("source")),
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
}
