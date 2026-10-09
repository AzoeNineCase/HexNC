package com.hexnc.music.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.hexnc.music.data.local.LrcParser
import com.hexnc.music.data.model.Lyrics

/**
 * Lyrics are stored as their **raw** documents (lrc / tlyric / romalrc / yrc) and re-parsed on load.
 * That keeps the on-disk format independent of the parser and means a parser improvement applies
 * retroactively to everything already cached.
 */
class LyricDao(private val helper: HexNCDatabase) {

    /** Where the lyric text came from, so the UI can offer "replace with imported .lrc". */
    enum class Origin(val key: String) {
        NETEASE("netease"),
        SIDECAR("sidecar"),
        EMBEDDED("embedded"),
        MANUAL("manual"),

        /** Fetched from LRCLIB, the key-less database used when a local file has no NetEase id. */
        LRCLIB("lrclib");

        companion object {
            fun fromKey(key: String?): Origin = entries.firstOrNull { it.key == key } ?: NETEASE
        }
    }

    fun save(songId: String, lyrics: Lyrics, origin: Origin = Origin.NETEASE) {
        val values = ContentValues().apply {
            put("song_id", songId)
            put("lrc", lyrics.rawLrc)
            put("tlyric", lyrics.rawTlyric)
            put("romalrc", lyrics.rawRoma)
            put("yrc", lyrics.rawYrc)
            put("offset_ms", lyrics.offsetMs)
            put("pure_music", if (lyrics.isPureMusic) 1 else 0)
            put("uncollected", if (lyrics.isUncollected) 1 else 0)
            put("origin", origin.key)
            put("updated_at", System.currentTimeMillis())
        }
        val db = helper.writableDatabase
        db.tx {
            val updated = db.update(HexNCDatabase.T_LYRICS, values, "song_id = ?", arrayOf(songId))
            if (updated == 0) {
                db.insertWithOnConflict(
                    HexNCDatabase.T_LYRICS, null, values, SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.execSQL(
                "UPDATE ${HexNCDatabase.T_SONGS} SET has_lyrics = ? WHERE id = ?",
                sqlArgs(if (lyrics.isEmpty) 0 else 1, songId)
            )
        }
    }

    /** Store a raw sidecar `.lrc` file the user imported for a local track. */
    fun saveRawLrc(songId: String, rawLrc: String, origin: Origin = Origin.SIDECAR) {
        val parsed = LrcParser.parseLrc(rawLrc)
        save(songId, parsed.copy(rawLrc = rawLrc), origin)
    }

    /**
     * Attach translation / romanisation documents to an existing entry without disturbing the base
     * lyric, which is how NetEase returns them (three separate fields of one response).
     */
    fun saveAuxiliary(songId: String, tlyric: String?, romalrc: String?) {
        val db = helper.writableDatabase
        db.execSQL(
            """
            UPDATE ${HexNCDatabase.T_LYRICS} SET tlyric = ?, romalrc = ?, updated_at = ? WHERE song_id = ?
            """.trimIndent(),
            sqlArgs(tlyric, romalrc, System.currentTimeMillis(), songId)
        )
    }

    /** Returns null when nothing is cached for [songId]. */
    fun load(songId: String): Lyrics? {
        var lrc: String? = null
        var tlyric: String? = null
        var roma: String? = null
        var yrc: String? = null
        var offset = 0L
        var pure = false
        var uncollected = false
        helper.readableDatabase.rawQuery(
            "SELECT * FROM ${HexNCDatabase.T_LYRICS} WHERE song_id = ?", arrayOf(songId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            lrc = c.strOrNull("lrc")
            tlyric = c.strOrNull("tlyric")
            roma = c.strOrNull("romalrc")
            yrc = c.strOrNull("yrc")
            offset = c.long("offset_ms")
            pure = c.bool("pure_music")
            uncollected = c.bool("uncollected")
        }

        val base = when {
            !yrc.isNullOrBlank() -> LrcParser.parseYrc(yrc, offset)
            !lrc.isNullOrBlank() -> LrcParser.parseLrc(lrc, offset)
            else -> Lyrics.EMPTY
        }
        if (base.isEmpty && tlyric.isNullOrBlank() && roma.isNullOrBlank()) return null
        return LrcParser.merge(base, tlyric, roma).copy(
            rawLrc = lrc ?: "",
            rawYrc = yrc,
            isPureMusic = pure || base.isPureMusic,
            isUncollected = uncollected
        )
    }

    fun origin(songId: String): Origin {
        helper.readableDatabase.rawQuery(
            "SELECT origin FROM ${HexNCDatabase.T_LYRICS} WHERE song_id = ?", arrayOf(songId)
        ).use { c -> return if (c.moveToFirst()) Origin.fromKey(c.getString(0)) else Origin.NETEASE }
    }

    fun has(songId: String): Boolean =
        helper.readableDatabase.scalarLong(
            "SELECT COUNT(*) FROM ${HexNCDatabase.T_LYRICS} WHERE song_id = ?", songId
        )?.let { it > 0 } ?: false

    fun delete(songId: String) {
        val db = helper.writableDatabase
        db.tx {
            db.delete(HexNCDatabase.T_LYRICS, "song_id = ?", arrayOf(songId))
            db.execSQL(
                "UPDATE ${HexNCDatabase.T_SONGS} SET has_lyrics = 0 WHERE id = ?", arrayOf(songId)
            )
        }
    }

    fun count(): Int =
        helper.readableDatabase.scalarLong("SELECT COUNT(*) FROM ${HexNCDatabase.T_LYRICS}")
            ?.toInt() ?: 0
}
