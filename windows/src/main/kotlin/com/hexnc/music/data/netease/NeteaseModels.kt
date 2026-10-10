package com.hexnc.music.data.netease

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire models for the NetEase Cloud Music API.
 *
 * The API is undocumented and inconsistent: the same concept arrives under different keys depending
 * on the endpoint generation (`ar` vs `artists`, `al` vs `album`, `dt` vs `duration`), optional
 * members are frequently *absent* rather than null, and some responses describe failures with a
 * non-200 `code` inside an HTTP 200 body. Every field here is therefore defaulted, unknown keys are
 * ignored, and the callers always go through [NeteaseClient]'s code checks.
 */
internal val NeteaseJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

// ------------------------------------------------------------------ shared shapes

@Serializable
data class NcArtist(
    val id: Long = 0L,
    val name: String = "",
)

@Serializable
data class NcAlbum(
    val id: Long = 0L,
    val name: String = "",
    val picUrl: String? = null,
    /** Only present on album-detail responses. */
    val artist: NcArtist? = null,
    /** Only present on the favourited-album list, which uses the plural key. */
    val artists: List<NcArtist> = emptyList(),
    val size: Int = 0,
) {
    /** Whichever of the two shapes this response used; empty when neither was sent. */
    val artistName: String
        get() = artist?.name ?: artists.firstOrNull()?.name.orEmpty()
}

/**
 * The signed-in account's favourited albums (`/api/album/sublist`).
 *
 * Only the paging flag and the album array are read: a page that carries an empty [albums] list is a
 * legitimate "nothing (more) favourited" rather than an error, so an empty result never throws.
 */
@Serializable
data class NcAlbumSublistResponse(
    val albums: List<NcAlbum> = emptyList(),
    val hasMore: Boolean = false,
    val code: Int = 200,
)

@Serializable
data class NcUser(
    val userId: Long = 0L,
    val nickname: String = "",
    val avatarUrl: String? = null,
    val signature: String? = null,
)

/**
 * A track as returned by `/api/v3/song/detail`.
 *
 * `ar`/`al` are the modern keys; `artists`/`album` are the legacy aliases still emitted by a few
 * endpoints, so both are parsed and [artistNames]/[albumName] prefer whichever is populated.
 */
@Serializable
data class NcSong(
    val id: Long = 0L,
    val name: String = "",
    val ar: List<NcArtist> = emptyList(),
    val al: NcAlbum = NcAlbum(),
    /** Legacy alias for [ar]. */
    val artists: List<NcArtist> = emptyList(),
    /** Legacy alias for [al]. */
    val album: NcAlbum = NcAlbum(),
    /** Duration in milliseconds. */
    val dt: Long = 0L,
    /** Legacy duration alias, in milliseconds. */
    val duration: Long = 0L,
    /** 0 free, 1 VIP, 4 paid album, 8 low-quality-free. */
    val fee: Int = 0,
    val no: Int = 0,
    val mv: Long = 0L,
    val alia: List<String> = emptyList(),
    val tns: List<String> = emptyList(),
) {
    val effectiveArtists: List<NcArtist> get() = ar.ifEmpty { artists }

    val effectiveAlbum: NcAlbum get() = if (al.id != 0L || al.name.isNotEmpty()) al else album

    val durationMs: Long get() = if (dt > 0L) dt else duration

    val artistNames: String get() = effectiveArtists.joinToString(" / ") { it.name }
}

@Serializable
data class NcTrackIdRef(
    val id: Long = 0L,
)

@Serializable
data class NcPlaylist(
    val id: Long = 0L,
    val name: String = "",
    @SerialName("coverImgUrl") val coverImgUrl: String? = null,
    val description: String? = null,
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    val creator: NcUser = NcUser(),
    val tracks: List<NcSong> = emptyList(),
    @SerialName("trackIds") val trackIds: List<NcTrackIdRef> = emptyList(),
    val subscribed: Boolean = false,
    /** 5 = "liked songs", 4 = MV, 3 = radio. */
    @SerialName("specialType") val specialType: Int = 0,
    val privacy: Int = 0,
    @SerialName("updateTime") val updateTime: Long = 0L,
)

/**
 * The playlist's track ids, in service order.
 *
 * `trackIds` is the complete list and is what every probed response actually carries; the inline
 * `tracks` array is only a server-capped sample, so it serves as a fallback for older shapes only.
 */
internal fun NcPlaylist.trackIdList(): List<Long> =
    trackIds.mapNotNull { ref -> ref.id.takeIf { it > 0L } }
        .ifEmpty { tracks.map { it.id }.filter { it > 0L } }

// ------------------------------------------------------------------ endpoint envelopes

@Serializable
data class NcSongDetailResponse(
    val songs: List<NcSong> = emptyList(),
    val privileges: List<NcPrivilege> = emptyList(),
    val code: Int = 200,
)

@Serializable
data class NcPrivilege(
    val id: Long = 0L,
    val fee: Int = 0,
    val maxbr: Int = 0,
    @SerialName("playMaxbr") val playMaxBr: Int = 0,
    @SerialName("maxBrLevel") val maxBrLevel: String? = null,
    @SerialName("playMaxBrLevel") val playMaxBrLevel: String? = null,
)

@Serializable
data class NcPlaylistDetailResponse(
    val playlist: NcPlaylist? = null,
    val privileges: List<NcPrivilege> = emptyList(),
    val code: Int = 200,
)

@Serializable
data class NcUserPlaylistResponse(
    val more: Boolean = false,
    val playlist: List<NcPlaylist> = emptyList(),
    val code: Int = 200,
)

@Serializable
data class NcAccountResponse(
    val account: NcAccount? = null,
    val profile: NcProfile? = null,
    val code: Int = 200,
)

@Serializable
data class NcAccount(
    val id: Long = 0L,
    val userName: String = "",
    val type: Int = 0,
    val status: Int = 0,
)

@Serializable
data class NcProfile(
    /** `/api/user/playlist` takes this as `uid`. */
    val userId: Long = 0L,
    val nickname: String = "",
    val avatarUrl: String? = null,
    val signature: String? = null,
    val backgroundUrl: String? = null,
    val follows: Int = 0,
    val followeds: Int = 0,
)

/**
 * One entry of `/api/song/enhance/player/url/v1`.
 *
 * [code] is per-track: `200` means the URL is usable, `404` means the account cannot play this
 * track at all (VIP-only / region-locked), and the URL is null in that case.
 */
@Serializable
data class NcSongUrl(
    val id: Long = 0L,
    val url: String? = null,
    /** Bitrate in bits per second. */
    val br: Int = 0,
    val size: Long = 0L,
    val md5: String? = null,
    val code: Int = 0,
    val fee: Int = 0,
    /** The tier actually granted — may be lower than the tier that was requested. */
    val level: String? = null,
    /** Container: `mp3`, `flac`, `m4a`. */
    val type: String? = null,
    val encodeType: String? = null,
    /** URL lifetime in seconds; NetEase currently issues 1200 s. */
    val expi: Long = 0L,
    val gain: Float = 0f,
    val peak: Float = 0f,
    val freeTrialInfo: NcFreeTrialInfo? = null,
) {
    val isPlayable: Boolean get() = !url.isNullOrEmpty() && (code == 200 || code == 0)
}

@Serializable
data class NcFreeTrialInfo(
    val start: Long = 0L,
    val end: Long = 0L,
)

@Serializable
data class NcSongUrlResponse(
    val data: List<NcSongUrl> = emptyList(),
    val code: Int = 200,
)

@Serializable
data class NcLyricText(
    val version: Int = 0,
    val lyric: String = "",
)

@Serializable
data class NcLyricUser(
    val id: Long = 0L,
    val nickname: String = "",
    val avatarUrl: String? = null,
)

@Serializable
data class NcLyricResponse(
    val lrc: NcLyricText? = null,
    val tlyric: NcLyricText? = null,
    val romalrc: NcLyricText? = null,
    /** Word-by-word lyrics, only on `/api/song/lyric/v1` over eapi. */
    val yrc: NcLyricText? = null,
    val klyric: NcLyricText? = null,
    /** Instrumental track. */
    val nolyric: Boolean = false,
    /** Lyrics not yet collected by NetEase. */
    val uncollected: Boolean = false,
    val needDesc: Boolean = false,
    val lyricUser: NcLyricUser? = null,
    val transUser: NcLyricUser? = null,
    val code: Int = 200,
)

@Serializable
data class NcSearchResponse(
    val result: NcSearchResult? = null,
    val code: Int = 200,
)

@Serializable
data class NcSearchResult(
    val songCount: Int = 0,
    val songs: List<NcSong> = emptyList(),
    val albumCount: Int = 0,
    val albums: List<NcAlbum> = emptyList(),
    val artistCount: Int = 0,
    val artists: List<NcArtist> = emptyList(),
    val playlistCount: Int = 0,
    val playlists: List<NcPlaylist> = emptyList(),
)

@Serializable
data class NcQrKeyResponse(
    val unikey: String? = null,
    val code: Int = 200,
)

/**
 * `/api/login/qrcode/client/login` poll result.
 *
 * `800` expired · `801` awaiting scan · `802` scanned, awaiting confirmation · `803` authorised.
 *
 * A negative `code` — in practice `-462` — is the service's risk-control gate asking for a captcha
 * rather than reporting a QR state; the payload then carries [data] naming the page to open.
 */
@Serializable
data class NcQrPollResponse(
    val code: Int = 0,
    val message: String? = null,
    /** On success the server also echoes the full cookie string. */
    val cookie: String? = null,
    val data: NcVerifyChallenge? = null,
)

/**
 * The captcha challenge returned alongside a risk-control code such as `-462`.
 *
 * Observed shape:
 * `{"verifyType":40,"verifyId":1007602,"verifyUrl":"https://st.music.163.com/encrypt-pages",
 *   "blockText":"验证成功后，可进行下一步操作哦~","verifyToken":"00.40.<hex>.<digits>"}`
 */
@Serializable
data class NcVerifyChallenge(
    val verifyType: Int = 0,
    val verifyId: Long = 0L,
    /** Landing page for the challenge, e.g. `https://st.music.163.com/encrypt-pages`. */
    val verifyUrl: String? = null,
    val blockText: String? = null,
    val verifyToken: String? = null,
) {
    /** [verifyUrl] when it actually carries something; null otherwise. */
    val challengeUrl: String? get() = verifyUrl?.takeIf { it.isNotBlank() }
}

@Serializable
data class NcLoginResponse(
    val code: Int = 200,
    val message: String? = null,
    val msg: String? = null,
    val account: NcAccount? = null,
    val profile: NcProfile? = null,
    /** Present on successful QR/password login. */
    val cookie: String? = null,
    /** Present when the attempt was stopped by risk control (`-462`) rather than by bad credentials. */
    val data: NcVerifyChallenge? = null,
    val bindings: List<NcBinding> = emptyList(),
)

@Serializable
data class NcBinding(
    val bindingType: Int = 0,
    val url: String? = null,
    val userId: Long = 0L,
    val expiresIn: Int = 0,
)

@Serializable
data class NcAlbumDetailResponse(
    val album: NcAlbum? = null,
    val songs: List<NcSong> = emptyList(),
    val code: Int = 200,
)

@Serializable
data class NcArtistTopSongsResponse(
    val songs: List<NcSong> = emptyList(),
    val more: Boolean = false,
    val code: Int = 200,
)

@Serializable
data class NcArtistDetailResponse(
    val artist: NcArtist? = null,
    val hotSongs: List<NcSong> = emptyList(),
    val more: Boolean = false,
    val code: Int = 200,
)

/** Generic "did the write succeed" envelope used by the like / playlist-write endpoints. */
@Serializable
data class NcWriteResponse(
    val code: Int = 200,
    val message: String? = null,
    val msg: String? = null,
    /** `playlist/manipulate/tracks` reports 512 when the track list must be resent duplicated. */
    val data: kotlinx.serialization.json.JsonElement? = null,
)
