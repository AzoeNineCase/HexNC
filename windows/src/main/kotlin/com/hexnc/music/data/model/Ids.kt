package com.hexnc.music.data.model

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Primary-key construction for every entity HexNC stores.
 *
 * Ids are namespaced by source so that the same logical song can exist twice — once as a local file
 * and once as a NetEase stream — without either row shadowing the other. They are also *stable*:
 * re-importing the same file or re-syncing the same playlist must produce the same id, which is what
 * makes the `INSERT … ON CONFLICT` upserts in the DAOs idempotent.
 */
object Ids {

    const val LOCAL_PREFIX = "local:"
    const val NETEASE_PREFIX = "ne:"

    /** `local:<sha1 of the normalized path/uri>`. */
    fun localSongId(pathOrUri: String): String = LOCAL_PREFIX + sha1(normalize(pathOrUri))

    /** `ne:<neteaseId>`. */
    fun neteaseSongId(neteaseId: Long): String = NETEASE_PREFIX + neteaseId

    /**
     * A NetEase playlist keeps the same id shape as its songs, which is safe because the two live in
     * different tables.
     */
    fun neteasePlaylistId(neteaseId: Long): String = NETEASE_PREFIX + neteaseId

    /** A fresh id for a playlist the user creates on the device. */
    fun localPlaylistId(): String = LOCAL_PREFIX + UUID.randomUUID().toString().replace("-", "")

    /**
     * The reserved id of the auto-maintained "all local music" playlist.
     *
     * A UUID never produces this value (its alphabet is hex only), so the row can be recognised
     * without a schema change and can never collide with a user-created playlist.
     */
    const val ALL_LOCAL_PLAYLIST_ID = "local:all-local-music"

    /** True when [id] belongs to a NetEase-sourced row. */
    fun isNetease(id: String): Boolean = id.startsWith(NETEASE_PREFIX)

    /**
     * Normalises a path or URI before hashing so that cosmetically different spellings of the same
     * file collapse to one row: `content://` URIs get their query stripped and case-folded, file
     * paths get separators unified on top of case folding.
     */
    private fun normalize(pathOrUri: String): String {
        val trimmed = pathOrUri.trim()
        val withoutQuery = if (trimmed.startsWith("content://", ignoreCase = true)) {
            trimmed.substringBefore('?')
        } else {
            trimmed
        }
        return withoutQuery.replace('\\', '/').lowercase(Locale.ROOT)
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte -> append(HEX[(byte.toInt() shr 4) and 0x0F]); append(HEX[byte.toInt() and 0x0F]) }
        }
    }

    private const val HEX = "0123456789abcdef"
}
