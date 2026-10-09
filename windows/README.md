# HexNC for Windows · 桌面版

网易云音乐 + 本地曲库的桌面播放器。界面、数据层与 Android 版 **HexNC** 同源，播放与文件交互按 Windows 重写。

> This is the Windows desktop port of HexNC. The UI and data layers are the same sources as the
> Android build; playback, file picking and the login browser are rewritten for the desktop.

---

## 中文

### 这是什么

`D:\AI Project\HexNC For Windows` 是 Android 版 HexNC 的 Windows 复刻：同一套界面代码（Compose Multiplatform 下的 `androidx.compose.*`）、同一套网易云协议实现、同一套 SQLite 表结构与歌词解析，只把真正依赖 Android 的部分换掉了。数据存放在 `%APPDATA%\HexNC`。

### 与 Android 版的一致性

| 能力 | 说明 |
| --- | --- |
| 网易云登录 | 扫码 / 手机号密码 / Cookie 三种方式，扫码二维码由 Skia 现场编码渲染 |
| 浏览器登录 | 打开系统默认浏览器登录网易云，再粘贴 `MUSIC_U`（或整条 Cookie）导入会话 |
| 歌单同步 | 登录后拉取「我喜欢的音乐」与自建/收藏歌单 |
| 曲库 | 本地歌曲、收藏、搜索、排序、批量操作 |
| 本地导入 | 扫描 `音乐` 文件夹、自选文件夹、自选文件（音频 + 同名 `.lrc` 歌词），也可在设置页单独为本地歌曲扫描歌词 |
| 歌词 | LRC / 逐字（yrc）/ 翻译 / 罗马音解析与排版，可调字号、行距、显示项；本地找不到歌词时自动到网易云与 LRCLIB 搜索，只有匹配度足够高（综合 ≥ 0.86 且标题 ≥ 0.90）才自动导入，否则在播放页提供「在线搜索」面板由你挑选 |
| 歌词滚动 | 居中自动跟随 + 逐字卡拉 OK 渐变擦除，可手动拖动、拖动后 5 秒恢复跟随、点行跳转；只有纯文本歌词（无时间轴）时改为整篇列表显示，不做假滚动 |
| 自定义封面 | 歌曲与歌单可指定本地图片作封面 |
| Playlist | 本地歌单新建 / 改名 / 增删歌 / 删除 / 排序；「全部本地音乐」是自动维护的保留歌单，汇总所有扫描到的本地歌曲，不支持改名或删除 |
| 播放 | 顺序 / 列表循环 / 单曲循环 / 随机，播放失败自动跳过并说明原因（文件丢失、格式不支持、网络失败、明文被拦截分别提示）；DSD（.dsf/.dff）会在播放前明确提示并跳过，192 kHz 及以上会按设备支持的采样率转换后播放 |
| 搜索 | 网易云综合搜索（歌曲 / 专辑 / 歌手 / 歌单）与本地库过滤 |
| 界面 | Miuix 0.9.4 + 极光渐变主题，深色 / 浅色 / AMOLED、动态取色、跟随封面取色 |

### 桌面端优化

- **真正的窗口**：1180×780 可缩放，`Window` + Compose Desktop，关闭时优雅释放播放器与数据库。
- **键盘快捷键**：空格 播放/暂停，`Ctrl+←/→` 上一首/下一首，`Ctrl+↑/↓` 音量 ±5%，`Ctrl+M` 静音。事件只在组件未消费时才处理，因此在搜索框里按空格仍然是输入空格。
- **原生文件对话框**：文件夹/文件选择走 Swing/AWT 原生对话框（并安装系统 Look and Feel，不让 Windows 上出现 Metal 风格的选择框），没有 Android 的运行时存储权限流程。
- **自带播放引擎**：用 Java Sound + SPI 解码器（mp3spi / vorbisspi / jflac / JDK 内置）替代 media3/ExoPlayer，在专用音频线程上推流。
- **超高采样率兼容**：设备不接受文件自身采样率时（192 kHz 本地文件是常见例子），交给 Java Sound 转换到设备支持的 48 kHz / 44.1 kHz 再输出，而不是整曲报错；普通文件仍走原来的零转换路径。
- **在线歌曲落盘缓存**：网易云直链约 20 分钟过期且不能直接给 Java Sound 播，因此首次播放即下载到本地（`<键>-<音质>` 缓存，1 GB LRU 上限），换来的好处是拖动进度秒响应、无网络卡顿，并会在开播下一首前预取。
- **音量滑杆**：播放页与设置页各有一条音量滑杆，播放中实时生效（不重启引擎）。
- **缓存管理**：设置页显示媒体缓存占用并可一键清空。
- **歌词在线补全**：本地扫描没有找到 `.lrc` 时，用「歌名 + 歌手」并发查询网易云（eapi，最多 15 条）与 LRCLIB（按名称与时长索引，不需要登录、不需要账号），在本地按标题 / 歌手 / 时长打分排序；达到门槛才自动写入，否则在播放页列出候选（歌名、歌手 · 专辑、时长、来源），点一下即导入。同一首歌 10 分钟内不会重复请求，离线时失败可重试。
- **登录用真浏览器**：桌面没有 WebView，直接用系统浏览器登录网易云，再粘贴 Cookie 导入 —— 2FA、验证码、以及「本机浏览器已登录」都能用上。

### 构建与运行

工具链：JDK 21、Gradle 9.8.0、Kotlin 2.4.20、Compose Multiplatform 1.12.1、Miuix 0.9.4。

```bat
:: 下面是作者本机便捷脚本的调用方式（路径按自己的环境修改）
cmd /c "buildwin.cmd :run"                 :: 直接运行
cmd /c "buildwin.cmd :test"                :: 跑单元测试
cmd /c "buildwin.cmd :createDistributable" :: 生成可运行的应用目录
cmd /c "buildwin.cmd :packageMsi"          :: 生成 MSI 安装包
```

仓库里**没有附带 gradle wrapper**，`buildwin.cmd` 也只是作者机器上的一个几行封装（内部调用固定路径的
Gradle）。克隆后请使用本机 Gradle 9.8 + JDK 21：

```bash
gradle -p . :test
gradle -p . :packageMsi
```

`createDistributable` 的产物在 `build\compose\binaries\main\app\HexNC\`（**221.7 MB**，内置完整运行时），双击其中的 `HexNC.exe` 即可运行，不需要本机装 Java；`packageMsi` 产出的安装包是 `build\compose\binaries\main\msi\HexNC-1.0.0.msi`（**106.3 MB**），已作为 Release 附件发布。WiX 不需要自己装：插件会自行下载 `wix311-binaries.zip` 到 Gradle 缓存（第一次构建时多花几分钟）。

应用目录里的运行时**包含全部 JDK 模块**（`includeAllModules = true`）。这不是浪费：jlink 只会打包它能从类路径推断出的模块，而 JDBC 兼容层调用的是 `java.sql.DriverManager`，第一次打包出来的应用启动即崩溃并抛出 `NoClassDefFoundError: java/sql/DriverManager`（`:run` 用的是完整工具链 JDK，所以看不出这个问题）。多带约 60 MB 换掉这一整类故障。

**打包版是 GUI 程序，没有控制台**：如果启动时抛异常，窗口会直接消失。因此 `main()` 安装了默认未捕获异常处理器，把堆栈追加到 `%APPDATA%\HexNC\crash.log` 并弹窗告知路径。

首次运行会在 `%APPDATA%\HexNC` 下创建 `hexnc.db`、`prefs\`、`covers\`、`media\`。

### 测试

**158 个测试 / 15 个测试类，全部通过**（`cmd /c "buildwin.cmd :test"`，实测 0 失败 0 错误）：

| 测试类 | 数量 |
| --- | --- |
| `data.lyrics.LyricMatchTest` | 24 |
| `data.local.LrcParserTest` | 21 |
| `data.netease.NeteaseCryptoTest` | 12 |
| `data.netease.NeteaseLoginGateTest` | 12 |
| `data.model.IdsTest` | 12 |
| `data.netease.NeteaseJsonTest` | 11 |
| `data.model.LyricProgressTest` | 11 |
| `data.model.LyricsTest` | 11 |
| `data.lyrics.LrclibJsonTest` | 8 |
| `data.netease.StreamUrlsTest` | 8 |
| `data.netease.NeteasePlaylistIdsTest` | 7 |
| `data.netease.RequestIdTest` | 7 |
| `playback.PlaybackMessageTest` | 6 |
| `data.local.DsdSourceTest` | 5 |
| `data.netease.NeteaseEndpointTest` | 3 |

测试与 Android 版共用同一套源码；唯一改写的是 `PlaybackMessageTest`：media3 被移出后，播放错误码由 `playback.PlaybackErrorCode` 提供（沿用 ExoPlayer 的取值 2001 网络失败 / 2002 超时 / 2004 HTTP 状态码错误 / 2005 文件不存在 / 2006 无权限 / 2007 明文被拦截 / 3001 解码失败），被测试锁住的是「错误码 → 用户可见提示」的映射本身。本次同步新增的三类测试是 `data.lyrics.LyricMatchTest`（标题 / 歌手 / 时长打分与自动导入门槛）、`data.lyrics.LrclibJsonTest`（LRCLIB 响应解析）与 `data.local.DsdSourceTest`（DSD 扩展名识别），另有 `LrcParserTest` 的纯文本歌词、`IdsTest` 的保留歌单 id、`LyricsTest` 的无时间轴判定共九例新增。

### 目录结构

```
src\main\kotlin\
  android\                            兼容外壳：让 data/ 层源码与 Android 完全一致
    content\Context.kt                Context + SharedPreferences（Properties 文件，带类型前缀）
    net\Uri.kt                        Uri（file: / 路径）
  androidx\compose\ui\platform\
    LocalContext.kt                  桌面 Compose 没有的 LocalContext，用同名包补齐
  com\hexnc\music\
    Main.kt                          入口：窗口、系统 Look and Feel、快捷键、装配 CompositionLocal
    HexNCApp.kt                      Context 实现 + 懒建 AppContainer + %APPDATA% 数据目录
    BuildConfig.kt                   VERSION_NAME / VERSION_CODE
    di\                              与 Android 版逐字节相同的依赖装配
    data\
      db\                            SQLite 表结构与四个 DAO（与 Android 逐字节相同）
        sqlite\                       JDBC 版 Cursor / SQLiteDatabase / SQLiteOpenHelper / ContentValues
      local\                         扫描器（jaudiotagger 读标签）、封面仓库、LRC 解析、DSD 识别
      lyrics\                        歌词匹配打分（LyricMatch）与 LRCLIB 客户端
      model\                         Song / Playlist / Lyrics / Ids / 歌词进度
      netease\                       eapi/weapi 加密、登录、接口、JSON 模型、直链 https 化
      prefs\                         设置存储（含桌面新增 volume）
      repo\                          曲库仓库、网易云仓库、在线歌词仓库
    playback\
      AudioEngine.kt                 Java Sound 播放引擎（专用线程、暂停/跳转/增益、采样率转换）
      MediaCache.kt                  直链下载与 1 GB LRU 缓存
      PlaybackController.kt          队列/顺序/随机/错误跳过/快照，与 Android 版同样的公开 API
      PlaybackMessages.kt            用户可见的播放状态码
    ui\                              与 Android 版同源的界面 + 桌面文件对话框
src\test\kotlin\                       与 Android 版同源的单元测试
```

### 已知限制

- **构建路径**：`createDistributable` 的应用目录跑起来即用，MSI 由 `packageMsi` 自动配好 WiX 生成；唯一的前提是本机能访问 Maven Central 与 GitHub（首次构建时要下 WiX）。
- **解码格式**：Java Sound 能解 MP3（mp3spi）、FLAC（jflac）、OGG（vorbisspi）与 WAV/AIFF/AU（JDK）。网易云的 `standard`/`higher`/`exhigh` 是 MP3，`lossless`/`hires` 是 FLAC，都在覆盖范围内；但本地曲库里的 **m4a / aac / opus / wma / ape 目前没有解码器**，这类文件会被跳过。**DSD（.dsf / .dff）** 同样没有解码器，但会先被识别出来、明确提示后跳过，而不是丢一个解码错误；**192 kHz 及以上**的 PCM 文件会先转换到设备支持的采样率（48 kHz / 44.1 kHz）再播放。
- **在线歌词**：补全结果取决于网易云与 LRCLIB 是否收录了那首曲目，且必须能联网；网易云搜索本身有风控与限速，因此同一首歌 10 分钟内只会请求一次，失败可以重试。请求只包含「歌名 + 歌手 + 专辑」文本，不上传文件内容、路径或任何账号信息（LRCLIB 无需登录）。
- **系统集成**：没有任务栏媒体控制、全局媒体键、SMTC 与托盘图标；这些是 Android 版也没有的，如需可后续加。
- **淡入淡出**：设置项为与 Android 版保持一致的保留项，桌面引擎尚未实现交叉淡入淡出。

---

## English

### What this is

`D:\AI Project\HexNC For Windows` is a full Windows port of the Android HexNC: the same UI sources
(Compose Multiplatform exposes the same `androidx.compose.*` packages), the same NetEase protocol
implementations, the same SQLite schema and the same lyric parsers — only the genuinely
platform-bound pieces are rewritten. Data lives in `%APPDATA%\HexNC`.

### Feature parity

| Area | Notes |
| --- | --- |
| NetEase sign-in | QR code / phone+password / cookie; the QR image is encoded and rendered through Skia |
| Browser sign-in | Opens your default browser on the NetEase login page, then imports the pasted `MUSIC_U` (or the whole cookie) |
| Playlist sync | Pulls “liked songs” plus your created and subscribed playlists |
| Library | Local songs, favourites, search, sorting, bulk actions |
| Local import | Scans the `Music` folder, or a folder/file you pick (audio plus a sidecar `.lrc`); Settings can also scan the library for lyric files on its own |
| Lyrics | LRC / word-level `yrc` / translation / romanisation, with adjustable size, line spacing and toggles; when nothing local exists the app searches NetEase and LRCLIB and only auto-imports a strong match (score ≥ 0.86 with a title score ≥ 0.90) — otherwise the player offers a “Search lyrics” picker |
| Lyric scroll | Centred auto-follow plus a word-by-word karaoke wipe; drag to browse, following resumes 5 s after the last drag, tap a line to seek; a plain un-timed sheet is shown as one list instead of being forced to scroll |
| Custom covers | Per-song and per-playlist cover override from a local image |
| Playlists | Create / rename / add / remove / reorder / delete local playlists; “All local music” is an auto-maintained reserved playlist holding every scanned local song and cannot be renamed or deleted |
| Playback | Sequence / repeat-all / repeat-one / shuffle, with automatic skip-on-error and a reason for the failure (missing file, unsupported format, network failure and blocked cleartext each get their own message); DSD (`.dsf`/`.dff`) is reported and skipped before playback, and 192 kHz-and-above audio is converted to a rate the device accepts |
| Search | NetEase combined search (songs / albums / artists / playlists) plus local filtering |
| UI | Miuix 0.9.4 with the aurora gradient theme, dark/light/AMOLED, dynamic colour, cover-seeded colour |

### Desktop-specific work

- **A real window** — 1180×780 and resizable; the player and database are released on close.
- **Keyboard shortcuts** — space toggles playback, `Ctrl+←/→` skip tracks, `Ctrl+↑/↓` nudge the volume by 5%, `Ctrl+M` mutes. The handler only runs for events no component consumed, so a space typed in the search field still inserts a space.
- **Native file dialogs** — folder and file pickers are Swing/AWT dialogs (with the system look and feel installed so a Windows chooser does not render in Metal); no Android runtime-permission dance at all.
- **Its own playback engine** — Java Sound plus SPI decoders (mp3spi / vorbisspi / jflac / JDK built-ins) in place of media3/ExoPlayer, feeding the audio device from one dedicated audio thread.
- **Very high sample rates** — when the device refuses the file's own rate (a 192 kHz local file is the common case) Java Sound converts it to 48 kHz / 44.1 kHz before output instead of failing the whole track; ordinary files keep the original zero-conversion path.
- **On-disk media cache** — NetEase stream URLs expire in ~20 minutes and Java Sound cannot play a URL, so a track is downloaded on first play (keyed `<id>-<quality>`, 1 GB LRU cap). The payoff is instant seeking, no network stutter, and a prefetch of the next track.
- **Volume slider** — in the player pane and in Settings, applied live while playing (the engine is not restarted).
- **Cache management** — Settings shows the cache size and clears it in one click.
- **Online lyric completion** — when no local `.lrc` exists, the song title and artist are sent to NetEase (eapi, up to 15 hits) and LRCLIB (indexed by name and duration, no login or account needed) in parallel, and the local ranker scores each hit on title / artist / duration. Only a hit above the threshold is imported automatically; everything else is listed in the player (title, artist · album, duration, source) for one-tap import. A song is not asked for twice within 10 minutes, and an offline failure can be retried.
- **A real browser for sign-in** — there is no WebView on the desktop, so sign-in opens the system browser and the session is imported by pasting the cookie — which gets 2FA, captcha and “already signed in on this PC” for free.

### Build and run

Toolchain: JDK 21, Gradle 9.8.0, Kotlin 2.4.20, Compose Multiplatform 1.12.1, Miuix 0.9.4.

```bat
:: how the author's local helper script is invoked (edit the path for your environment)
cmd /c "buildwin.cmd :run"                 :: run it
cmd /c "buildwin.cmd :test"                :: unit tests
cmd /c "buildwin.cmd :createDistributable" :: runnable application image
cmd /c "buildwin.cmd :packageMsi"          :: MSI installer
```

The repository ships **no gradle wrapper**, and `buildwin.cmd` is just a few-line wrapper around a
fixed Gradle path on the author's machine. After cloning, use your own Gradle 9.8 + JDK 21:

```bash
gradle -p . :test
gradle -p . :packageMsi
```

`createDistributable` writes to `build\compose\binaries\main\app\HexNC\` (**221.7 MB**, self-contained
runtime); run `HexNC.exe` from there and no local Java installation is needed. `packageMsi` writes
`build\compose\binaries\main\msi\HexNC-1.0.0.msi` (**106.3 MB**), attached to the GitHub Release.
WiX does not have to be installed: the plugin downloads `wix311-binaries.zip` into the Gradle
cache by itself (the first build spends a few extra minutes on it).

The bundled runtime **includes every JDK module** (`includeAllModules = true`). That is not waste: jlink
bundles only the modules it can infer from the classpath, and the JDBC compatibility layer calls
`java.sql.DriverManager`, so the first packaged image died at startup with
`NoClassDefFoundError: java/sql/DriverManager` — invisible from `:run`, which uses the full toolchain
JDK. Roughly 60 MB buys away that entire class of failure.

**The packaged build is a GUI app with no console**: an exception during startup would simply make the
window vanish. `main()` therefore installs a default uncaught-exception handler that appends the stack
trace to `%APPDATA%\HexNC\crash.log` and shows a dialog naming that path.

The first launch creates `hexnc.db`, `prefs\`, `covers\` and `media\` under `%APPDATA%\HexNC`.

### Tests

**158 tests in 15 classes, all passing** (`cmd /c "buildwin.cmd :test"` — measured: 0 failures,
0 errors): the table above lists each class. The suite is the same source set as the Android build;
the only rewrite is `PlaybackMessageTest`, because media3 is gone and the playback error codes now
come from `playback.PlaybackErrorCode` (ExoPlayer's numbering: 2001 network failed, 2002 timeout,
2004 bad HTTP status, 2005 file not found, 2006 no permission, 2007 cleartext blocked, 3001 decoding
failed). What the test pins is the error-code → user-visible-message mapping. The three classes added
by this sync are `data.lyrics.LyricMatchTest` (title / artist / duration scoring and the auto-import
bar), `data.lyrics.LrclibJsonTest` (LRCLIB response parsing) and `data.local.DsdSourceTest` (DSD
extension detection), alongside nine new cases in `LrcParserTest` (plain text), `IdsTest` (the
reserved playlist id) and `LyricsTest` (the un-timed sheet).

### Known limitations

- **Build path** — the `createDistributable` image runs as-is and `packageMsi` wires WiX up by itself; the only prerequisite is reachable Maven Central and GitHub (WiX is fetched on the first build).
- **Codecs** — Java Sound decodes MP3 (mp3spi), FLAC (jflac), OGG (vorbisspi) and WAV/AIFF/AU (JDK). Every NetEase tier is covered (`standard`/`higher`/`exhigh` are MP3, `lossless`/`hires` are FLAC), but **m4a / aac / opus / wma / ape files in a local library have no decoder** and are skipped. **DSD (`.dsf` / `.dff`)** has no decoder either, but it is recognised and reported before playback instead of surfacing as a decoding error, and **192 kHz-and-above** PCM is converted to a rate the device accepts (48 kHz / 44.1 kHz) first.
- **Online lyrics** — completion depends on NetEase or LRCLIB actually cataloguing the track and on a working network; NetEase search is rate-limited and risk-controlled, so a given song is only asked about once per 10 minutes and a failure can be retried. Requests contain only title / artist / album text — never file contents, paths or account data (LRCLIB needs no login).
- **Shell integration** — no taskbar transport controls, no global media keys, no SMTC, no tray icon. Android had none of these either; they could be added later.
- **Crossfade** — kept in Settings for parity with Android; the desktop engine does not implement it yet.
