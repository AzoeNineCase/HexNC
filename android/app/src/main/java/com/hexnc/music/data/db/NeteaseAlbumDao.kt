package com.hexnc.music.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * One entry of the favourited-album cache.
 *
 * Deliberately flatter than `NcAlbum`: the cached copy only needs what a list row draws, and a flat
 * row survives a model change without a database migration.
 */
data class CachedAlbum(
    val id: Long,
    val name: String,
    val artistName: String,
    val coverUrl: String?,
    val trackCount: Int,
    val publishTime: Long,
    val syncedAt: Long,
)

/**
 * The cache behind the favourited-albums page.
 *
 * NetEase's sublist is a browse surface that answers with an empty page whenever it feels like it,
 * so the page renders from this table and treats the network as a refresh. That is what turns "断网
 * 进入收藏专辑页变成空白" into "显示上次同步的内容 + 重试提示".
 *
 * Nothing here is user-authored — the server list is authoritative — so a refresh replaces the
 * whole table in one transaction rather than reconciling row by row.
 */
class NeteaseAlbumDao(private val helper: HexNCDatabase) {

    /** Replaces the cache with [albums], preserving the server's ordering. Returns rows written. */
    fun replaceAll(albums: List<CachedAlbum>): Int {
        val db = helper.writableDatabase
        return db.tx {
            db.delete(HexNCDatabase.T_NC_ALBUMS, null, null)
            if (albums.isEmpty()) return@tx 0
            var written = 0
            albums.forEachIndexed { index, album ->
                val values = ContentValues().apply {
                    put("id", album.id)
                    put("name", album.name)
                    put("artist", album.artistName)
                    put("cover_url", album.coverUrl)
                    put("track_count", album.trackCount)
                    put("publish_time", album.publishTime)
                    put("position", index)
                    put("synced_at", album.syncedAt)
                }
                if (db.insertWithOnConflict(
                        HexNCDatabase.T_NC_ALBUMS,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    ) >= 0
                ) {
                    written++
                }
            }
            written
        }
    }

    /** Appends one page without dropping what is already cached, for incremental loading. */
    fun appendPage(albums: List<CachedAlbum>, startPosition: Int): Int {
        if (albums.isEmpty()) return 0
        val db = helper.writableDatabase
        return db.tx {
            var written = 0
            albums.forEachIndexed { index, album ->
                val values = ContentValues().apply {
                    put("id", album.id)
                    put("name", album.name)
                    put("artist", album.artistName)
                    put("cover_url", album.coverUrl)
                    put("track_count", album.trackCount)
                    put("publish_time", album.publishTime)
                    put("position", startPosition + index)
                    put("synced_at", album.syncedAt)
                }
                if (db.insertWithOnConflict(
                        HexNCDatabase.T_NC_ALBUMS,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    ) >= 0
                ) {
                    written++
                }
            }
            written
        }
    }

    fun all(): List<CachedAlbum> {
        val out = ArrayList<CachedAlbum>()
        helper.readableDatabase.rawQuery(
            "SELECT * FROM ${HexNCDatabase.T_NC_ALBUMS} ORDER BY position ASC, id ASC",
            null,
        ).use { c -> while (c.moveToNext()) out += c.toCachedAlbum() }
        return out
    }

    fun count(): Int =
        helper.readableDatabase
            .scalarLong("SELECT COUNT(*) FROM ${HexNCDatabase.T_NC_ALBUMS}")?.toInt() ?: 0

    /** When the cache was last written; 0 means "never", which the UI renders as "未同步". */
    fun lastSyncedAt(): Long =
        helper.readableDatabase
            .scalarLong("SELECT MAX(synced_at) FROM ${HexNCDatabase.T_NC_ALBUMS}") ?: 0L

    fun clear() {
        helper.writableDatabase.delete(HexNCDatabase.T_NC_ALBUMS, null, null)
    }

    private fun Cursor.toCachedAlbum(): CachedAlbum = CachedAlbum(
        id = long("id"),
        name = str("name"),
        artistName = str("artist"),
        coverUrl = strOrNull("cover_url"),
        trackCount = int("track_count"),
        publishTime = long("publish_time"),
        syncedAt = long("synced_at"),
    )
}