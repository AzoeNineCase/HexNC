package com.hexnc.music.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import com.hexnc.music.data.lyrics.LyricSource
import com.hexnc.music.data.model.Playlist
import com.hexnc.music.data.prefs.AppLanguage
import java.util.Locale

/** The two languages HexNC ships in-app. */
enum class Language {
    CHINESE,
    ENGLISH;

    companion object {
        /** True when the system locale is any Chinese variant (zh, zh-CN, zh-TW, zh-Hans, …). */
        fun systemPrefersChinese(locale: Locale = Locale.getDefault()): Boolean =
            locale.language.equals("zh", ignoreCase = true)

        /** Turn the stored preference into a concrete language, honouring the "follow system" value. */
        fun resolve(pref: AppLanguage, locale: Locale = Locale.getDefault()): Language = when (pref) {
            AppLanguage.CHINESE -> CHINESE
            AppLanguage.ENGLISH -> ENGLISH
            AppLanguage.SYSTEM -> if (systemPrefersChinese(locale)) CHINESE else ENGLISH
        }
    }
}

/**
 * In-app string catalog.
 *
 * HexNC does not rely on Android's `values-zh-rCN` resource switching for its UI text: the app has
 * a language picker that must take effect immediately, on every API level, without recreating the
 * activity or pulling in `AppCompatDelegate`. A plain Kotlin catalog read through a
 * [androidx.compose.runtime.CompositionLocal] gives instant switching and keeps both languages
 * side by side, so a missing translation is impossible by construction.
 *
 * Android XML resources are still used for the launcher label and the notification channel names.
 */
class Strings(val language: Language) {

    val isChinese: Boolean get() = language == Language.CHINESE

    /** Pick the Chinese or English variant. */
    fun p(zh: String, en: String): String = if (isChinese) zh else en

    // ------------------------------------------------------------------ generic
    val appName get() = "HexNC"
    val ok get() = p("确定", "OK")
    val cancel get() = p("取消", "Cancel")
    val confirm get() = p("确认", "Confirm")
    val save get() = p("保存", "Save")
    val delete get() = p("删除", "Delete")
    val edit get() = p("编辑", "Edit")
    val done get() = p("完成", "Done")
    val close get() = p("关闭", "Close")
    val back get() = p("返回", "Back")
    val retry get() = p("重试", "Retry")
    val loading get() = p("加载中…", "Loading…")
    val search get() = p("搜索", "Search")
    val clear get() = p("清除", "Clear")
    val all get() = p("全部", "All")
    val more get() = p("更多", "More")
    val refresh get() = p("刷新", "Refresh")
    val rename get() = p("重命名", "Rename")
    val remove get() = p("移除", "Remove")
    val add get() = p("添加", "Add")
    val apply get() = p("应用", "Apply")
    val reset get() = p("重置", "Reset")
    val none get() = p("无", "None")
    val unknown get() = p("未知", "Unknown")
    val unknownArtist get() = p("未知艺术家", "Unknown artist")
    val unknownAlbum get() = p("未知专辑", "Unknown album")
    val somethingWentWrong get() = p("出错了", "Something went wrong")
    val networkError get() = p("网络连接失败，请检查网络后重试", "Network unreachable. Check your connection and retry.")
    val copied get() = p("已复制", "Copied")

    fun songs(count: Int) = p("$count 首歌曲", if (count == 1) "1 song" else "$count songs")

    fun items(count: Int) = p("$count 项", if (count == 1) "1 item" else "$count items")

    fun playlists(count: Int) = p("$count 个歌单", if (count == 1) "1 playlist" else "$count playlists")

    // ------------------------------------------------------------------ navigation
    val tabLibrary get() = p("音乐库", "Library")
    val tabPlaylists get() = p("歌单", "Playlists")
    val tabSearch get() = p("搜索", "Search")
    val tabSettings get() = p("设置", "Settings")

    // ------------------------------------------------------------------ library
    val library get() = p("音乐库", "Library")
    val songs get() = p("歌曲", "Songs")
    val albums get() = p("专辑", "Albums")
    val artists get() = p("艺术家", "Artists")
    val folders get() = p("文件夹", "Folders")
    val favorites get() = p("我喜欢的音乐", "Liked songs")
    val recentlyAdded get() = p("最近添加", "Recently added")
    val localMusic get() = p("本地音乐", "Local music")
    val neteaseMusic get() = p("网易云音乐", "NetEase Cloud Music")
    val sourceAll get() = p("全部来源", "All sources")
    val sourceLocal get() = p("仅本地", "Local only")
    val sourceNetease get() = p("仅云端", "Cloud only")
    val sortBy get() = p("排序方式", "Sort by")
    val sortTitleAsc get() = p("标题 A → Z", "Title A → Z")
    val sortTitleDesc get() = p("标题 Z → A", "Title Z → A")
    val sortArtistAsc get() = p("艺术家 A → Z", "Artist A → Z")
    val sortAlbumAsc get() = p("专辑 A → Z", "Album A → Z")
    val sortAddedDesc get() = p("最近添加", "Recently added")
    val sortDurationAsc get() = p("时长 短 → 长", "Duration short → long")
    val sortDurationDesc get() = p("时长 长 → 短", "Duration long → short")
    val playAll get() = p("播放全部", "Play all")
    val shuffleAll get() = p("随机播放", "Shuffle all")
    val addToQueue get() = p("添加到播放队列", "Add to queue")
    val playNext get() = p("下一首播放", "Play next")
    val addedToQueue get() = p("已加入播放队列", "Added to the queue")
    val libraryEmptyTitle get() = p("音乐库还是空的", "Your library is empty")
    val libraryEmptyBody get() = p(
        "导入本地音乐，或登录网易云账号同步歌单。",
        "Import music from this device, or sign in to NetEase to sync your playlists.",
    )
    val importMusic get() = p("导入音乐", "Import music")
    val signInNetease get() = p("登录网易云", "Sign in to NetEase")
    val albumCount get() = p("张专辑", "albums")
    val artistCount get() = p("位艺术家", "artists")

    // ------------------------------------------------------------------ playlists
    val playlistsTitle get() = p("歌单", "Playlists")
    val myPlaylists get() = p("我的歌单", "My playlists")
    val neteasePlaylists get() = p("网易云歌单", "NetEase playlists")
    val createPlaylist get() = p("新建歌单", "New playlist")
    val playlistName get() = p("歌单名称", "Playlist name")
    val playlistNameHint get() = p("给这个歌单起个名字", "Name this playlist")
    val deletePlaylist get() = p("删除歌单", "Delete playlist")
    val deletePlaylistConfirm get() = p("确定要删除这个歌单吗？此操作无法撤销。", "Delete this playlist? This cannot be undone.")
    val renamePlaylist get() = p("重命名歌单", "Rename playlist")
    val playlistEmpty get() = p("这个歌单还没有歌曲", "This playlist is empty")
    val addSongs get() = p("添加歌曲", "Add songs")
    val removeFromPlaylist get() = p("从歌单移除", "Remove from playlist")
    val addToPlaylist get() = p("添加到歌单", "Add to playlist")
    val addToFavorites get() = p("喜欢", "Like")
    val removeFromFavorites get() = p("取消喜欢", "Unlike")
    val addedToPlaylist get() = p("已添加到歌单", "Added to the playlist")
    val syncPlaylist get() = p("同步歌单", "Sync playlist")
    val syncAllPlaylists get() = p("同步全部歌单", "Sync all playlists")
    val syncing get() = p("正在同步…", "Syncing…")
    val syncDone get() = p("同步完成", "Sync complete")
    val syncFailed get() = p("同步失败", "Sync failed")

    /** Shown after the automatic import that follows a sign-in. */
    fun importedLibrary(playlists: Int, tracks: Int) =
        p("已导入 $playlists 个歌单 · $tracks 首歌曲", "Imported $playlists playlists · $tracks tracks")
    val lastSynced get() = p("上次同步", "Last synced")
    val neverSynced get() = p("尚未同步", "Never synced")
    val createdBy get() = p("创建者", "Created by")
    val trackCount get() = p("歌曲数", "Tracks")
    val subscribed get() = p("已收藏", "Subscribed")
    val likedSongs get() = p("我喜欢的音乐", "Liked songs")
    val allLocalMusic get() = p("全部本地音乐", "All local music")
    val allLocalMusicSummary get() = p(
        "自动收纳所有扫描到的本地歌曲",
        "Every local song the app has scanned, kept in one place",
    )
    val playlistCreated get() = p("歌单已创建", "Playlist created")
    val playlistDeleted get() = p("歌单已删除", "Playlist deleted")
    val playlistRenamed get() = p("歌单已重命名", "Playlist renamed")
    val songRemovedFromPlaylist get() = p("已从歌单移除", "Removed from the playlist")
    val playlistCover get() = p("歌单封面", "Playlist cover")

    /**
     * Display name of a playlist.
     *
     * The two reserved playlists are stored under a fixed name — or, for liked songs, none at all —
     * so their title has to come from the catalog rather than the database, otherwise switching the
     * app language would leave them stamped with the language they were created in.
     */
    fun playlistTitle(playlist: Playlist): String = when {
        playlist.isLikedSongs -> likedSongs
        playlist.isAllLocal -> allLocalMusic
        else -> playlist.name
    }

    // ------------------------------------------------------------------ search
    val searchHint get() = p("搜索歌曲、专辑或艺术家", "Search songs, albums or artists")
    val searchNeteaseHint get() = p("搜索网易云音乐", "Search NetEase Cloud Music")
    val searchSongs get() = p("单曲", "Songs")
    val searchAlbums get() = p("专辑", "Albums")
    val searchArtists get() = p("艺术家", "Artists")
    val searchPlaylists get() = p("歌单", "Playlists")
    val searchCategory get() = p("搜索范围", "Search in")
    val recentSearches get() = p("最近搜索", "Recent searches")
    val clearHistory get() = p("清除历史记录", "Clear history")
    val noResults get() = p("没有找到结果", "No results found")
    val searchStartHint get() = p("输入关键词开始搜索", "Type something to start searching")
    val searchFailed get() = p("搜索失败，请稍后重试", "Search failed. Try again later.")
    val localResults get() = p("本地结果", "In your library")
    val neteaseResults get() = p("网易云结果", "From NetEase")

    // ------------------------------------------------------------------ settings
    val settings get() = p("设置", "Settings")
    val appearance get() = p("外观", "Appearance")
    val theme get() = p("主题模式", "Theme mode")
    val themeSystem get() = p("跟随系统", "Follow system")
    val themeLight get() = p("浅色", "Light")
    val themeDark get() = p("深色", "Dark")
    val auroraTheme get() = p("极光主题", "Aurora theme")
    val auroraThemeSummary get() = p("选择一套配色，界面会即时更新", "Pick a palette — the UI updates instantly")
    val amoledBlack get() = p("纯黑背景", "Pure black background")
    val amoledBlackSummary get() = p("深色模式下使用纯黑底色，更省电", "Use true black in dark mode to save power")
    val languageTitle get() = p("语言", "Language")
    val languageSystem get() = p("跟随系统", "Follow system")
    val languageChinese get() = p("简体中文", "简体中文")
    val languageEnglish get() = p("English", "English")
    val preview get() = p("预览", "Preview")

    val playback get() = p("播放", "Playback")
    val audioQuality get() = p("音质", "Audio quality")
    val qualityStandard get() = p("标准", "Standard")
    val qualityHigher get() = p("较高", "Higher")
    val qualityExhigh get() = p("极高", "Very high")
    val qualityLossless get() = p("无损", "Lossless")
    val qualityHires get() = p("Hi-Res", "Hi-Res")
    val qualityHintNetease get() = p("仅对网易云在线歌曲生效；实际音质取决于账号权限。", "Applies to NetEase streams only; the actual tier depends on your account.")
    val crossfade get() = p("淡入淡出", "Crossfade")
    val crossfadeOff get() = p("关闭", "Off")
    fun seconds(value: Int) = p("$value 秒", "$value s")
    val autoPlayNext get() = p("自动播放下一首", "Auto-play the next track")
    val skipOnError get() = p("播放失败时自动跳过", "Skip a track that fails to play")
    val volume get() = p("音量", "Volume")
    val mediaCache get() = p("媒体缓存", "Media cache")
    fun cacheUsage(size: String) = p("已占用 $size（在线歌曲播过一次就留在本地，便于秒开和拖动）", "$size on disk (a streamed track is kept locally after the first play, so seeking is instant)")
    val clearCache get() = p("清空媒体缓存", "Clear the media cache")
    val cacheCleared get() = p("媒体缓存已清空", "Media cache cleared")

    val lyrics get() = p("歌词", "Lyrics")
    val lyricFontSize get() = p("歌词文字大小", "Lyric text size")
    val lyricTranslation get() = p("显示翻译", "Show translation")
    val lyricRoman get() = p("显示罗马音", "Show romanisation")
    val lyricLineSpacing get() = p("行距", "Line spacing")
    val lyricPreviewText get() = p("极光掠过山脊，夜色缓缓退去", "Aurora over the ridge, the night draws back")
    val lyricPreviewTrans get() = p("预览歌词效果", "Lyric preview")

    val librarySection get() = p("音乐库", "Library")
    val rescanLocal get() = p("重新扫描本地音乐", "Rescan local music")
    val scanLocalLyrics get() = p("扫描本地歌词", "Scan local lyrics")
    val scanLocalLyricsSummary get() = p(
        "先查找同名 .lrc 文件，再到网易云和 LRCLIB 搜索",
        "Match a local .lrc file first, then search NetEase and LRCLIB",
    )
    val scanLocalLyricsRunning get() = p("正在扫描…", "Scanning…")
    val scanLocalLyricsOnline get() = p("正在联网搜索…", "Searching online…")
    val lyricsScanSummary get() = p(
        "本地 %1\$d 首 · 在线 %2\$d 首",
        "%1\$d local · %2\$d online",
    )
    val lyricsScanNothing get() = p("没有找到新的歌词文件", "No new lyric files found")
    val clearLibrary get() = p("清空音乐库", "Clear the library")
    val clearLibraryConfirm get() = p(
        "将删除全部已导入的歌曲、歌词与歌单数据。此操作无法撤销。",
        "This deletes every imported song, lyric and playlist. It cannot be undone.",
    )
    val libraryCleared get() = p("音乐库已清空", "Library cleared")

    val account get() = p("账号", "Account")
    val neteaseAccount get() = p("网易云账号", "NetEase account")
    val notSignedIn get() = p("未登录", "Not signed in")
    val signedInAs get() = p("已登录：%s", "Signed in as %s")
    val signOut get() = p("退出登录", "Sign out")
    val signOutConfirm get() = p("退出后在线歌曲将无法播放，本地音乐不受影响。", "Online playback stops after signing out. Local music is unaffected.")
    val signedOut get() = p("已退出登录", "Signed out")
    val uid get() = p("用户 ID", "User ID")

    val about get() = p("关于", "About")
    val version get() = p("版本", "Version")
    val disclaimer get() = p("免责声明", "Disclaimer")
    val disclaimerBody get() = p(
        "HexNC 是第三方非官方客户端，与网易云音乐官方无任何关联。所有在线内容版权归原权利人所有。" +
            "接口基于公开逆向资料实现，可能随时失效；请勿用于商业用途。",
        "HexNC is an unofficial third-party client with no affiliation to NetEase Cloud Music. " +
            "All online content remains the property of its rights holders. The API is based on publicly " +
            "documented reverse engineering and may break at any time. Do not use it commercially.",
    )
    val madeWith get() = p("使用 Jetpack Compose 与 Miuix 构建", "Built with Jetpack Compose and Miuix")

    // ------------------------------------------------------------------ sign in
    val signIn get() = p("登录", "Sign in")
    val signInSubtitle get() = p(
        "登录后可同步歌单、收藏与播放云端歌曲。登录信息仅保存在本机。",
        "Sign in to sync playlists and favourites and to stream cloud tracks. Credentials stay on this device.",
    )
    val signInWithQr get() = p("扫码登录", "QR code")
    val signInWithPassword get() = p("账号密码", "Password")
    val email get() = p("邮箱", "Email")
    val phone get() = p("手机号", "Phone number")
    val password get() = p("密码", "Password")
    val emailOrPhone get() = p("邮箱或手机号", "Email or phone number")
    val signInAction get() = p("登录", "Sign in")
    val signingIn get() = p("正在登录…", "Signing in…")
    val signInFailed get() = p("登录失败：%s", "Sign-in failed: %s")
    val wrongCredentials get() = p("账号或密码错误", "Incorrect account or password")
    val captchaRequired get() = p(
        "账号密码与扫码登录都可能被网易云风控拦截（返回 -462），此时请改用 Cookie 登录",
        "Risk control can block password and QR sign-in alike (code -462); use Cookie sign-in instead",
    )
    val qrTitle get() = p("扫码登录", "Scan to sign in")
    val qrHint get() = p("打开网易云音乐 App，扫描二维码并在手机上确认", "Open the NetEase Cloud Music app, scan the code and confirm on your phone")
    val qrWaiting get() = p("等待扫码…", "Waiting for scan…")
    val qrScanned get() = p("已扫描，请在手机上确认", "Scanned — confirm on your phone")
    val qrExpired get() = p("二维码已失效", "QR code expired")
    val qrRefresh get() = p("点击刷新", "Tap to refresh")
    val qrSuccess get() = p("登录成功", "Signed in")
    val generateQrFailed get() = p("无法生成二维码，请检查网络", "Could not create a QR code — check your connection")

    val signInWithCookie get() = p("Cookie 登录", "Cookie")
    val cookieTitle get() = p("粘贴网易云 Cookie", "Paste your NetEase cookies")
    val cookieHint get() = p(
        "在浏览器中登录 music.163.com，从开发者工具里复制 Cookie，或只粘贴 MUSIC_U 的值。Cookie 仅保存在本机，不会上传。",
        "Sign in to music.163.com in a browser, copy the cookies from devtools, or paste just the MUSIC_U value. Cookies stay on this device and are never uploaded.",
    )
    val cookieLabel get() = p("Cookie 或 MUSIC_U", "Cookies or MUSIC_U")
    val cookieAction get() = p("导入并登录", "Import and sign in")
    val cookieImporting get() = p("正在验证…", "Verifying…")
    val cookieEmpty get() = p("请先粘贴 Cookie", "Paste a cookie first")

    /** `%s` is the underlying reason from the service. */
    val cookieFailed get() = p("Cookie 无效或已过期：%s", "Cookie invalid or expired: %s")

    val qrVerifyRequired get() = p(
        "网易云要求先完成安全验证，扫码登录已被风控拦截",
        "NetEase requires a security check — QR sign-in was blocked by risk control",
    )
    val qrVerifyAction get() = p("打开验证页面", "Open verification page")
    val qrVerifyHint get() = p(
        "验证后若仍无法登录，请改用下方的 Cookie 登录。",
        "If sign-in still fails after verifying, use Cookie sign-in below.",
    )

    val signInWithBrowser get() = p("浏览器登录", "Browser")
    val browserTitle get() = p("在浏览器中登录网易云", "Sign in to NetEase in your browser")
    val browserHint get() = p(
        "点击下面的按钮，用你的默认浏览器登录 music.163.com；登录成功后回到这里，把会话粘贴到下方输入框。登录信息仅保存在本机。",
        "Use the button below to sign in to music.163.com in your default browser, then come back and paste the session into the field underneath. The session stays on this device.",
    )
    val browserOpenAction get() = p("打开网易云登录页", "Open the NetEase sign-in page")
    val browserPasteHint get() = p(
        "在浏览器中按 F12 → Application → Cookies → https://music.163.com，复制 MUSIC_U 的值（或整条 Cookie），粘贴到上面。",
        "In the browser press F12 → Application → Cookies → https://music.163.com, copy the MUSIC_U value (or the whole Cookie header) and paste it above.",
    )
    val browserLoading get() = p("正在加载登录页面…", "Loading the sign-in page…")
    val browserImportAction get() = p("导入登录状态", "Import session")
    val browserCookieMissing get() = p(
        "粘贴的内容里没有找到 MUSIC_U，请确认复制的是登录后的 Cookie",
        "No MUSIC_U found in what you pasted — make sure you copied the cookie from a signed-in session",
    )
    val browserFailed get() = p("登录页面加载失败，请检查网络", "Could not load the sign-in page — check your connection")

    /**
     * Shown on every sign-in tab. The freeze reports are secondhand (third-party client trackers),
     * so the wording says "may" rather than asserting a behaviour NetEase never documented.
     */
    val accountRiskWarning get() = p(
        "注意：网易云对第三方登录风控严格。反复尝试扫码或密码登录可能触发安全验证，甚至导致账号被临时冻结（有 3–7 天的报告）。优先使用浏览器登录或 Cookie 登录。",
        "Note: NetEase applies strict risk control to third-party sign-in. Repeated QR or password attempts can trigger a security check or even a temporary account freeze (3–7 days has been reported). Prefer browser or Cookie sign-in.",
    )

    // ------------------------------------------------------------------ player
    val nowPlaying get() = p("正在播放", "Now playing")
    val upNext get() = p("播放队列", "Up next")
    val play get() = p("播放", "Play")
    val pause get() = p("暂停", "Pause")
    val previous get() = p("上一首", "Previous")
    val next get() = p("下一首", "Next")
    val shuffle get() = p("随机", "Shuffle")
    val repeatSequence get() = p("顺序播放", "Play in order")
    val repeatAll get() = p("列表循环", "Repeat all")
    val repeatOne get() = p("单曲循环", "Repeat one")
    val queueEmpty get() = p("播放队列为空", "The queue is empty")
    val clearQueue get() = p("清空队列", "Clear the queue")
    val nowPlayingLyrics get() = p("歌词", "Lyrics")
    val noLyrics get() = p("暂无歌词", "No lyrics")
    val pureMusic get() = p("纯音乐，请欣赏", "Instrumental — enjoy the music")
    val uncollectedLyrics get() = p("歌词尚未收录", "Lyrics not collected yet")
    val lyricsLoading get() = p("正在加载歌词…", "Loading lyrics…")
    val lyricsLoadFailed get() = p("歌词加载失败", "Could not load the lyrics")
    val lyricsOffset get() = p("歌词偏移", "Lyric offset")
    val lyricsOffsetReset get() = p("重置偏移", "Reset offset")
    val lyricsFollowCurrent get() = p("跟随歌词", "Follow lyrics")
    val lyricSearchTitle get() = p("在线搜索歌词", "Search lyrics online")
    val lyricSearchHint get() = p("歌曲名 歌手", "Song title artist")
    val lyricSearchAction get() = p("在线搜索", "Search online")
    val lyricSearchRunning get() = p("搜索中…", "Searching…")
    val lyricSearchEmpty get() = p("没有找到匹配的歌词", "No matching lyrics found")
    val lyricSearchFailed get() = p("搜索失败，请检查网络后重试", "Search failed — check your network and try again")
    val lyricSyncedTag get() = p("逐行歌词", "Synced")
    val lyricPlainTag get() = p("纯文本", "Plain text")
    val lyricInstrumentalTag get() = p("纯音乐", "Instrumental")
    val lyricSourceNetease get() = p("网易云", "NetEase")
    fun lyricSourceName(source: LyricSource): String = when (source) {
        LyricSource.NETEASE -> lyricSourceNetease
        LyricSource.LRCLIB -> "LRCLIB"
    }
    fun offsetMs(value: Long) = (if (value > 0) "+" else "") + value + " ms"
    val buffering get() = p("缓冲中…", "Buffering…")
    val trackUnavailable get() = p("该歌曲当前无法播放", "This track is not playable right now")
    val trackUnavailableVip get() = p("该歌曲需要网易云会员", "This track requires a NetEase VIP subscription")
    val playbackFailed get() = p("播放失败：%s", "Playback failed: %s")
    val playbackCleartext get() = p(
        "播放失败：音频直链是明文 HTTP，被系统安全策略拦截",
        "Playback failed: the audio link is plain HTTP and was blocked by the system security policy",
    )
    val playbackNetwork get() = p(
        "播放失败：无法连接网易云服务器，请检查网络",
        "Playback failed: could not reach the NetEase server — check your network",
    )
    val playbackUnknown get() = p(
        "播放失败：该音频无法解码，或来源已失效",
        "Playback failed: the audio could not be decoded, or the source is gone",
    )
    val currentTrack get() = p("当前播放", "Now playing")
    val removeFromQueue get() = p("从队列中移除", "Remove from the queue")
    val playbackFileMissing get() = p(
        "播放失败：找不到该音频文件，可能已被移动或删除",
        "Playback failed: the audio file is missing — it may have been moved or deleted",
    )
    val playbackUnsupported get() = p(
        "播放失败：不支持该音频格式（例如 DSD、超高采样率）",
        "Playback failed: this audio format is not supported (e.g. DSD, extreme sample rates)",
    )
    val playbackDsd get() = p(
        "DSD 格式（.dsf/.dff）暂不支持播放，已跳过",
        "DSD (.dsf/.dff) is not supported yet and was skipped",
    )

    // ------------------------------------------------------------------ covers
    val cover get() = p("封面", "Cover")
    val customCover get() = p("自定义封面", "Custom cover")
    val changeCover get() = p("更换封面", "Change cover")
    val pickFromGallery get() = p("从相册选择", "Choose from gallery")
    val pickFromFiles get() = p("从文件选择", "Choose from files")
    val resetCover get() = p("恢复默认封面", "Reset to the default cover")
    val coverUpdated get() = p("封面已更新", "Cover updated")
    val coverFailed get() = p("封面保存失败", "Could not save the cover")
    val coverForAlbum get() = p("应用到整张专辑", "Apply to the whole album")

    // ------------------------------------------------------------------ import
    val importTitle get() = p("导入本地音乐", "Import local music")
    val importFromDevice get() = p("扫描音乐文件夹", "Scan your music folder")
    val importFromDeviceSummary get() = p(
        "导入「音乐」文件夹里已有的音频，无需选择",
        "Import the audio already in your Music folder — no picker needed",
    )
    val importFromFolder get() = p("从文件夹导入", "Import from a folder")
    val importFromFolderSummary get() = p("选择一个文件夹，递归导入其中的音频与歌词", "Pick a folder and import every audio and lyric file inside it")
    val importFromFiles get() = p("选择文件", "Pick files")
    val importFromFilesSummary get() = p("手动选择一个或多个音频文件", "Select one or more audio files manually")
    val importing get() = p("正在导入…", "Importing…")
    val scanning get() = p("正在扫描…", "Scanning…")
    val importDone get() = p("导入完成", "Import complete")
    val importFound get() = p("已导入 %1\$d 首歌曲，%2\$d 份歌词", "Imported %1\$d songs and %2\$d lyric files")
    val importNothing get() = p("没有发现新的音乐文件", "No new audio files found")
    val importFailed get() = p("导入失败：%s", "Import failed: %s")
    val chooseCoverTitle get() = p("选择封面图片", "Choose a cover image")
    val chooseLyricTitle get() = p("选择歌词文件", "Choose a lyric file")
    val readingFolder get() = p("正在读取文件夹…", "Reading the folder…")
    val supportsFormats get() = p("支持 MP3 / FLAC / M4A / AAC / OGG / WAV / WMA / APE，并自动匹配同名 .lrc 歌词", "Supports MP3 / FLAC / M4A / AAC / OGG / WAV / WMA / APE, and picks up a matching .lrc file next to each track")

    // ------------------------------------------------------------------ misc
    val confirmDelete get() = p("确认删除", "Confirm deletion")
    val cannotBeUndone get() = p("此操作无法撤销。", "This cannot be undone.")
    val emptyPlaylistHint get() = p("去音乐库添加一些歌曲吧", "Add some songs from your library")
    val selectSongs get() = p("选择歌曲", "Select songs")
    val selectedCount get() = p("已选 %d 首", "%d selected")
    fun selected(n: Int) = p("已选 $n 首", "$n selected")
    val addN get() = p("添加", "Add")
    val noLocalSongs get() = p("本地还没有音乐", "No local music yet")
    val allSongs get() = p("全部歌曲", "All songs")
    val song get() = p("歌曲", "Song")
    val album get() = p("专辑", "Album")
    val artist get() = p("艺术家", "Artist")
    val duration get() = p("时长", "Duration")
    val quality get() = p("音质", "Quality")
    val source get() = p("来源", "Source")
    val actions get() = p("操作", "Actions")
    val showAll get() = p("显示全部", "Show all")
    val collapse get() = p("收起", "Collapse")
    val countSongs get() = p("歌曲", "Tracks")
}

/** Provides the active [Strings] to the composition. */
val LocalStrings = staticCompositionLocalOf { Strings(Language.ENGLISH) }
