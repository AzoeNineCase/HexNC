# HexNC for Windows · 桌面版

网易云音乐 + 本地曲库的桌面播放器。界面、数据层与 Android 版 **HexNC** 同源，播放与文件交互按 Windows 重写。

> This is the Windows desktop port of HexNC. The UI and data layers are the same sources as the
> Android build; playback, file picking and the login browser are rewritten for the desktop.

---

## 中文

### 这是什么

`D:\AI Project\HexNC\HexNC For Windows` 是 Android 版 HexNC 的 Windows 复刻：同一套界面代码（Compose Multiplatform 下的 `androidx.compose.*`）、同一套网易云协议实现、同一套 SQLite 表结构与歌词解析，只把真正依赖 Android 的部分换掉了。数据存放在 `%APPDATA%\HexNC`。

### 与 Android 版的一致性

| 能力 | 说明 |
| --- | --- |
| 网易云登录 | 扫码 / 手机号密码 / Cookie 三种方式，扫码二维码由 Skia 现场编码渲染 |
| 浏览器登录 | 打开系统默认浏览器登录网易云，再粘贴 `MUSIC_U`（或整条 Cookie）导入会话 |
| 歌单同步 | 登录后拉取「我喜欢的音乐」与自建/收藏歌单；歌单页在「网易云歌单」之后还有**「网易云专辑」**区，展示账号里收藏的专辑（`/api/album/sublist`，`api` 失败时回退 `eapi`），点按才按需导入整张专辑——收藏专辑本身不写曲库 |
| 曲库 | 本地歌曲、收藏、搜索、排序、批量操作；曲库页顶部有**「最近播放」**货架（按播放顺序保存最近 24 首，存在偏好设置里，不新建数据库表），**「最近添加」只列歌曲、只给 4 条** |
| 本地导入 | 扫描 `音乐` 文件夹、自选文件夹、自选文件（音频 + 同名 `.lrc` 歌词），也可在设置页单独为本地歌曲扫描歌词 |
| 歌词 | LRC / 逐字（yrc）/ 翻译 / 罗马音解析与排版，可调字号、行距、显示项；本地找不到歌词时自动到网易云与 LRCLIB 搜索，只有匹配度足够高（综合 ≥ 0.86 且标题 ≥ 0.90）才自动导入，否则在播放页提供「在线搜索」面板由你挑选 |
| 歌词滚动 | 居中自动跟随 + 逐字卡拉 OK 渐变擦除，可手动拖动、拖动后 5 秒恢复跟随、点行跳转；只有纯文本歌词（无时间轴）时改为整篇列表显示，不做假滚动 |
| 播放详情页 | 封面、进度、传输控制与歌词同屏：宽窗口左右分栏，窄窗口上下分区，歌词不再需要来回切换 |
| 自定义封面 | 歌曲与歌单可指定本地图片作封面 |
| Playlist | 本地歌单新建 / 改名 / 增删歌 / 删除 / 排序；「全部本地音乐」是自动维护的保留歌单，汇总所有扫描到的本地歌曲，不支持改名或删除 |
| 播放 | 顺序 / 列表循环 / 单曲循环 / 随机，播放失败自动跳过并说明原因（文件丢失、格式不支持、网络失败、明文被拦截分别提示）；DSD（`.dsf`/`.dff`）先解码成 PCM 再播放（DST 压缩的 DSD 无法解码，会明确提示并跳过），192 kHz 及以上会按设备支持的采样率转换后播放 |
| 搜索 | 网易云综合搜索（歌曲 / 专辑 / 歌手 / 歌单）与本地库过滤 |
| 界面 | Miuix 0.9.4 + 极光渐变主题，深色 / 浅色 / AMOLED、动态取色、跟随封面取色；**整体降饱和、加入灰度**——六套主题的 accent / deep / glow / blush 都只经过 `AuroraTheme.soft*`（`soften()`：按 Rec.601 亮度去饱和 34%，再与 `#7C8290` 混 10%），配色方案、渐变与封面占位图都读这四个入口 |

### 桌面端优化

- **真正的窗口**：1180×780 可缩放，`Window` + Compose Desktop，关闭时优雅释放播放器与数据库。
- **键盘快捷键**：空格 播放/暂停，`Ctrl+←/→` 上一首/下一首，`Ctrl+↑/↓` 音量 ±5%，`Ctrl+M` 静音。事件只在组件未消费时才处理，因此在搜索框里按空格仍然是输入空格。
- **自适应宽度布局**：窗口宽度 ≥ 840 dp 时主导航从底部移到左侧 `NavigationRail`（迷你播放条仍在底部），播放详情页 ≥ 900 dp 时变成「左封面与控制、右歌词」的分栏，窄窗口自动回到上下分区；列表在超宽窗口下限制到 1120 dp 阅读宽度并居中，播放详情页的宽布局也按同一宽度居中，免得标题和行尾按钮相隔上千 dp，也让播放页与列表页的内容列对齐。默认窗口 1180×780，打开就是宽屏布局。
- **降低饱和度**：整体配色统一走一遍 `soften()`（Rec.601 亮度去饱和 34% + 与 `#7C8290` 混 10%），六套主题全部覆盖；数值上白底对比度不降反升（最弱的北境主题 2.54 → 3.14），深色底允许小幅下降但都还在 4:1 以上（最大降幅北境 7.60 → 6.07）。`AuroraPaletteTest` 把这个变换钉住，因为"顺手读原色"是最容易犯的错——那会让渐变和封面占位图继续发光，只有配色方案变灰。
- **右键与常驻操作按钮**：任意歌曲行、歌单行都能右键唤出与长按相同的操作菜单（`PointerMatcher.mouse(PointerButton.Secondary)`），每行右侧另外有一个常驻 `⋮` 按钮——右键和长按都不足以被自己发现，所以才补了这个可见入口；长按本身保留。菜单内容仍是同一套 `TrackActionsSheet`（收藏、加入队列、加入歌单、换封面、导入标题、刷新歌词、删除）。
- **原生文件对话框**：文件夹/文件选择走 Swing/AWT 原生对话框（并安装系统 Look and Feel，不让 Windows 上出现 Metal 风格的选择框），没有 Android 的运行时存储权限流程。
- **自带播放引擎**：用 Java Sound + SPI 解码器（mp3spi / vorbisspi / jflac / JDK 内置）替代 media3/ExoPlayer，在专用音频线程上推流。
- **DSD 原生解码**：内置纯 Kotlin 的 DSF / DFF 解析与半带 FIR 抽取（DSD64/128/256 → 176.4 kHz 16-bit PCM），并按 Java Sound SPI 注册为 `AudioFileReader`（`META-INF/services`），所以 `AudioSystem.getAudioInputStream()` 拿到的就已经是 PCM，播放层代码完全不用改。
- **超高采样率兼容**：**超过 192 kHz 的采样率不再交给设备**——Windows 的混音器会对它其实渲染不了的采样率返回「支持」，所以文件自身的采样率只在不高于 192 kHz 时才作为首选，否则直接从目标采样率开始；候选格式会逐个真正 `open` 一次，设备拒绝就依次退到 48 kHz / 44.1 kHz（DSD 解码出的 176.4 kHz 也走这条路），而不是整曲报错。普通文件仍走原来的零转换路径。
- **在线歌曲落盘缓存**：网易云直链约 20 分钟过期且不能直接给 Java Sound 播，因此首次播放即下载到本地（`<键>-<音质>` 缓存，1 GB LRU 上限），换来的好处是拖动进度秒响应、无网络卡顿，并会在开播下一首前预取。
- **音量滑杆**：播放页与设置页各有一条音量滑杆，播放中实时生效（不重启引擎）。
- **缓存管理**：设置页显示媒体缓存占用并可一键清空。
- **歌词在线补全**：本地扫描没有找到 `.lrc` 时，用「歌名 + 歌手」并发查询网易云（eapi，最多 15 条）与 LRCLIB（按名称与时长索引，不需要登录、不需要账号），在本地按标题 / 歌手 / 时长打分排序；达到门槛才自动写入，否则在播放页列出候选（歌名、歌手 · 专辑、时长、来源），点一下即导入。同一首歌 10 分钟内不会重复请求，离线时失败可重试。
- **登录用真浏览器**：桌面没有 WebView，直接用系统浏览器登录网易云，再粘贴 Cookie 导入 —— 2FA、验证码、以及「本机浏览器已登录」都能用上。
- **能覆盖安装、也能卸载**：安装包每次构建换一个 ProductCode（jpackage 原来只按包名 + 版本推导，重建的 1.0.0 会带着已安装那个 1.0.0 的同一份 ProductCode，于是 `msiexec /i` 返回 0 却什么都不换），所以现在把新 MSI 装到旧版上会先删后装、只留一个「应用」条目；开始菜单里也多了一个「Uninstall HexNC」。细节与实测记录见「Windows 安装、覆盖安装与卸载」。

### 构建与运行

工具链：JDK 21、Gradle 9.8.0、Kotlin 2.4.20、Compose Multiplatform 1.12.1、Miuix 0.9.4。

```bat
:: 构建脚本在 D:\AI Project\.toolchain\buildwin.cmd，需要在该目录下调用
cmd /c "buildwin.cmd :run"                 :: 直接运行
cmd /c "buildwin.cmd :test"                :: 跑单元测试
cmd /c "buildwin.cmd :createDistributable" :: 生成可运行的应用目录
cmd /c "buildwin.cmd :packageMsi"          :: 生成 MSI 安装包
```

`createDistributable` 的产物在 `build\compose\binaries\main\app\HexNC\`（**221.7 MB**，内置完整运行时），双击其中的 `HexNC.exe` 即可运行，不需要本机装 Java；`packageMsi` 产出的安装包是 `build\compose\binaries\main\msi\HexNC-1.1.0.msi`（**106.5 MB**），也已复制一份到工程根目录。WiX 不需要自己装：插件会自行下载 `wix311-binaries.zip` 到 Gradle 缓存（第一次构建时多花几分钟），解开后在 `build\wix311\`（`candle.exe` / `light.exe`）。

### Windows 安装、覆盖安装与卸载

`packageMsi` 在 jpackage 出包之后会再跑一遍 **`packaging\windows\patch-msi.ps1`**（`build.gradle.kts` 里的 `doLast` 钩子，补丁失败即构建失败），用 `WindowsInstaller.Installer` COM 接口在事务里改 MSI 数据库的三处：

| 改动 | 为什么 |
| --- | --- |
| `Property.ProductCode` → 每次构建新生成一个 GUID | jpackage 只按「包名 + 版本」推导 ProductCode，所以重新构建的同一个版本都带着**已安装那一个的同一份 ProductCode**（最明显的就是拿重建的 1.0.0 去装已装的 1.0.0）：Windows Installer 把它当成同一个产品的维护模式，`msiexec /i` 返回 0 却**什么都没换**（实测安装前后都是 458 个文件 / 232,623,561 B，两个 sha256 完全一样，日志写着 `Skipping RemoveExistingProducts action: current configuration is maintenance mode or an uninstall`）。换成新 ProductCode、UpgradeCode 不变，安装程序就会把它当成同族里的旧产品，走 `RemoveExistingProducts` 先删后装 |
| `Upgrade` 里 `JP_UPGRADABLE_FOUND` 那行 Attributes `257 → 769`（加 `VersionMaxInclusive`） | jpackage 的模板给自己版本那行编译的是 `IncludeMaximum="no"`，于是同版本既不是「更旧」也不是「更新」，两行都不匹配、永远不会被移除；补上这一位后「≤ 当前版本」才真的匹配。`JP_DOWNGRADABLE_FOUND` 那行（Attributes 1）故意不动，而这张表只负责**探测**、jpackage 在这个包里没有生成任何拦截动作，所以**降级同样不会被拦**（实测见下） |
| `Shortcut` 增一行 `Uninstall HexNC` | 开始菜单原来只有「HexNC」，卸载只能去「设置 → 应用」里找。新链接指向 `[SystemFolder]msiexec.exe /x [ProductCode]`，挂在原来那个开始菜单快捷方式的组件上（MSI SQL 连 `Registry.Key` 列都不能引用，所以无法新建注册表键路径的组件），因此它跟应用快捷方式同生共死 |

**卸载入口**：开始菜单 →「HexNC」→「**Uninstall HexNC**」；或 设置 → 应用 → 已安装的应用 → HexNC → 卸载；或 `msiexec /x {ProductCode}`。

**覆盖安装**：直接双击新版 MSI（或 `msiexec /i`）即可，它会把旧版先卸掉再装，只留一个「应用」条目，不会出现两个并排的安装。同一个 MSI 重复运行只是维护模式空转（返回 0，文件不动）；**降级也不会被拦** —— 把 1.0.0 的 MSI 装到已装的 1.1.0 上实测返回 0，并且真的把应用换回了 1.0.0（`JP_DOWNGRADABLE_FOUND` 那行只做探测，这个 MSI 里没有对应的拦截动作）。换句话说：装上哪个版本，取决于你运行的是哪个 MSI。

实测（本机、非提权、`/qn`）：1.0.0 全新安装 → 应用条目 `HexNC 1.0.0`（`{AB338083-…}`）；再装 1.1.0 → 返回 0，条目只剩 `HexNC 1.1.0`（`{3B0E62BC-…}`），`HexNC-For-Windows-1.1.0-*.jar` 取代 1.0.0 的 jar，`HexNC.exe` 的 sha256 由 `8E4B71F0…` 变为 `27B65288…`；1.1.0 装回 1.0.0（降级）→ 返回 0，条目变回 `HexNC 1.0.0`；同一个 1.1.0 MSI 再跑一次 → 返回 0、维护模式空转；`msiexec /x {3B0E62BC-…}` → 返回 0，应用条目、`%LOCALAPPDATA%\HexNC`、开始菜单与桌面快捷方式全部消失，`D:\Config.Msi` 无残留。

注意：重装/修复请**用同一个路径与文件名**的 MSI —— 换个目录或改个名字再运行维护模式，Windows Installer 找不到它自己的源包，会以 1603 失败（这与补丁无关，原生 jpackage MSI 同样如此，本次实测踩到过一次）。

**仍未验证**：`packageExe`（Exe 安装包内嵌自己的 MSI，**不经过这个补丁**）；重新构建过的**同版本** MSI（ProductCode 与已装的同一个版本不同）覆盖安装时会不会同样先删后装，只有「同一个文件重复运行 = 空转」这一条实测过。另外 `RemoveExistingProducts` 仍保持在 `CostInitialize` 之前（沿用 jpackage 的顺序），所以新安装失败时不会回滚旧产品。

应用目录里的运行时**包含全部 JDK 模块**（`includeAllModules = true`）。这不是浪费：jlink 只会打包它能从类路径推断出的模块，而 JDBC 兼容层调用的是 `java.sql.DriverManager`，第一次打包出来的应用启动即崩溃并抛出 `NoClassDefFoundError: java/sql/DriverManager`（`:run` 用的是完整工具链 JDK，所以看不出这个问题）。多带约 60 MB 换掉这一整类故障。

**打包版是 GUI 程序，没有控制台**：如果启动时抛异常，窗口会直接消失。因此 `main()` 安装了默认未捕获异常处理器，把堆栈追加到 `%APPDATA%\HexNC\crash.log` 并弹窗告知路径。

首次运行会在 `%APPDATA%\HexNC` 下创建 `hexnc.db`、`prefs\`、`covers\`、`media\`。

### 测试

**180 个测试 / 19 个测试类，全部通过**（`cmd /c "buildwin.cmd :test"`，实测 0 失败 0 错误）：

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
| `data.local.dsd.DsdDecodeTest` | 11 |
| `data.lyrics.LrclibJsonTest` | 8 |
| `data.netease.StreamUrlsTest` | 8 |
| `data.netease.NeteasePlaylistIdsTest` | 7 |
| `data.netease.RequestIdTest` | 7 |
| `playback.PlaybackMessageTest` | 6 |
| `data.local.DsdSourceTest` | 5 |
| `playback.AudioOutputFormatTest` | 5 |
| `ui.theme.AuroraPaletteTest` | 4 |
| `data.netease.NeteaseEndpointTest` | 3 |
| `playback.DsdServiceProviderTest` | 2 |

测试与 Android 版共用同一套源码；唯一改写的是 `PlaybackMessageTest`：media3 被移出后，播放错误码由 `playback.PlaybackErrorCode` 提供（沿用 ExoPlayer 的取值 2001 网络失败 / 2002 超时 / 2004 HTTP 状态码错误 / 2005 文件不存在 / 2006 无权限 / 2007 明文被拦截 / 3001 解码失败），被测试锁住的是「错误码 → 用户可见提示」的映射本身。本次同步新增的三类测试是 `data.lyrics.LyricMatchTest`（标题 / 歌手 / 时长打分与自动导入门槛）、`data.lyrics.LrclibJsonTest`（LRCLIB 响应解析）与 `data.local.DsdSourceTest`（DSD 扩展名识别），另有 `LrcParserTest` 的纯文本歌词、`IdsTest` 的保留歌单 id、`LyricsTest` 的无时间轴判定共九例新增。

DSD 支持带来的三类新测试是 `data.local.dsd.DsdDecodeTest`（11 例：自造 DSF / DFF 容器解析、一阶 sigma-delta 正弦经半带抽取后仍然存在、双声道分离、DST 与假文件被拒绝、半带滤波器系数与抽取倍数）、`playback.AudioOutputFormatTest`（5 例：锁住「超过 192 kHz 绝不作为候选」的采样率阶梯，以及 176.4 kHz 输出仍可回退到 48 kHz / 44.1 kHz）与 `playback.DsdServiceProviderTest`（2 例：真的用 `AudioSystem.getAudioFileFormat()` 打开一个临时 `.dsf`，确认 SPI 注册生效能拿到 176.4 kHz PCM；同时确认非 DSD 文件仍由 JDK 自带解码器处理）。

降饱和那一次改动的护栏是 `ui.theme.AuroraPaletteTest`（4 例，与 Android 版逐字节相同的同一份文件）：颜色调校是最容易改错又看不见的一类改动（构造渐变时顺手读原色，配色方案变灰而渐变还是霓虹色），而它本身是纯数值函数，所以把「四个色位每一支都必须比原色更不饱和」「白底对比度不许下降、深色底允许小幅下降但必须 ≥ 4:1」「`gradientStops` 必须由 `soft*` 入口构造」三类断言钉进测试。

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
        dsd\                         纯 Kotlin 的 DSF / DFF 容器解析（DsdFormat）与半带 FIR 抽取（DsdDecoder）
      lyrics\                        歌词匹配打分（LyricMatch）与 LRCLIB 客户端
      model\                         Song / Playlist / Lyrics / Ids / 歌词进度
      netease\                       eapi/weapi 加密、登录、接口、JSON 模型、直链 https 化
      prefs\                         设置存储（含桌面新增 volume）
      repo\                          曲库仓库、网易云仓库、在线歌词仓库
    playback\
      AudioEngine.kt                 Java Sound 播放引擎（专用线程、暂停/跳转/增益、采样率转换）
      DsdAudioFileReader.kt          Java Sound SPI 提供者：AudioSystem 直接拿到 DSD 解出的 PCM
      MediaCache.kt                  直链下载与 1 GB LRU 缓存
      PlaybackController.kt          队列/顺序/随机/错误跳过/快照，与 Android 版同样的公开 API
      PlaybackMessages.kt            用户可见的播放状态码
    ui\                              与 Android 版同源的界面 + 桌面文件对话框
      platform\                      桌面专属：右键点击（PointerInput.kt）与 Swing/AWT 文件对话框
src\main\resources\
  META-INF\services\                  Java Sound SPI 注册（javax.sound.sampled.spi.AudioFileReader）
src\test\kotlin\                       与 Android 版同源的单元测试
packaging\windows\patch-msi.ps1        MSI 数据库后处理（每次构建的新 ProductCode、同版本可覆盖、卸载快捷方式）
```

### 已知限制

- **构建路径**：`createDistributable` 的应用目录跑起来即用，MSI 由 `packageMsi` 自动配好 WiX 生成；唯一的前提是本机能访问 Maven Central 与 GitHub（首次构建时要下 WiX）。
- **解码格式**：Java Sound 能解 MP3（mp3spi）、FLAC（jflac）、OGG（vorbisspi）与 WAV/AIFF/AU（JDK）。网易云的 `standard`/`higher`/`exhigh` 是 MP3，`lossless`/`hires` 是 FLAC，都在覆盖范围内；但本地曲库里的 **m4a / aac / opus / wma / ape 目前没有解码器**，这类文件会被跳过。**DSD（`.dsf` / `.dff`）** 现在会解码播放：DSF 与 DFF 容器由内置纯 Kotlin 解码器解析，DSD64 / DSD128 / DSD256 经半带 FIR 抽取成 176.4 kHz 16-bit PCM 后交给 Java Sound；只有 **DST 压缩的 DSD** 与结构损坏的文件无法解码，会明确提示后跳过，而不是丢一个解码错误。**192 kHz 及以上**的 PCM 文件一律不把原始采样率交给设备，直接转换到设备支持的采样率（48 kHz / 44.1 kHz）再播放。
- **在线歌词**：补全结果取决于网易云与 LRCLIB 是否收录了那首曲目，且必须能联网；网易云搜索本身有风控与限速，因此同一首歌 10 分钟内只会请求一次，失败可以重试。请求只包含「歌名 + 歌手 + 专辑」文本，不上传文件内容、路径或任何账号信息（LRCLIB 无需登录）。
- **网易云收藏专辑**：`/api/album/sublist` 在真实登录态下从未成功调用过（本机过不了网易云风控），因此只验证了请求形状、`api` 失败回退 `eapi`、以及读取失败时保留上一次列表而不是清空；收藏专辑是否与账号一致、以及「点按才导入整张专辑」是否符合预期，都只能在真机上确认。同一批里新增的「最近播放」货架也一样：记录点写在 `startAt()`（已经尝试打开播放的曲目就记一笔，包括随后下载失败的），这个顺序没有测试覆盖，只有排序逻辑由 `recentlyPlayed()` 按 id 列表回排保证。
- **降饱和调色**：只有数值被验证（`AuroraPaletteTest` 的四条断言 + 逐主题对比度计算），「看起来是否更舒服」只能人工看；本机无显示设备，因此没有任何截图证据。
- **系统集成**：没有任务栏媒体控制、全局媒体键、SMTC 与托盘图标；这些是 Android 版也没有的，如需可后续加。
- **淡入淡出**：设置项为与 Android 版保持一致的保留项，桌面引擎尚未实现交叉淡入淡出。
- **界面测试**：自适应布局、左侧导航栏、右键菜单与 `⋮` 按钮只由编译和手动运行确认，没有 UI 自动化测试（Compose UI 测试尚未接入 `:test`）。
- **安装包**：MSI 的安装、覆盖安装（1.1.0 over 1.0.0）、降级安装（1.0.0 over 1.1.0，实测会真的装回旧版）、同文件重复运行与卸载都已在本机实测（见「Windows 安装、覆盖安装与卸载」）；但 `packageExe` 没有打补丁，重建的同版本 MSI 覆盖安装也未实测。

---

## English

### What this is

`D:\AI Project\HexNC\HexNC For Windows` is a full Windows port of the Android HexNC: the same UI sources
(Compose Multiplatform exposes the same `androidx.compose.*` packages), the same NetEase protocol
implementations, the same SQLite schema and the same lyric parsers — only the genuinely
platform-bound pieces are rewritten. Data lives in `%APPDATA%\HexNC`.

### Feature parity

| Area | Notes |
| --- | --- |
| NetEase sign-in | QR code / phone+password / cookie; the QR image is encoded and rendered through Skia |
| Browser sign-in | Opens your default browser on the NetEase login page, then imports the pasted `MUSIC_U` (or the whole cookie) |
| Playlist sync | Pulls “liked songs” plus your created and subscribed playlists; the playlists page gained a **“NetEase albums”** section listing the albums favourited on the account (`/api/album/sublist`, falling back to `eapi`), and an album is imported on demand only when tapped — favouriting it never writes to the local library |
| Library | Local songs, favourites, search, sorting, bulk actions; the library page opens with a **“Recently played”** shelf (the last 24 ids in play order, kept in preferences rather than a new table) and **“Recently added” now lists songs only, 4 rows** |
| Local import | Scans the `Music` folder, or a folder/file you pick (audio plus a sidecar `.lrc`); Settings can also scan the library for lyric files on its own |
| Lyrics | LRC / word-level `yrc` / translation / romanisation, with adjustable size, line spacing and toggles; when nothing local exists the app searches NetEase and LRCLIB and only auto-imports a strong match (score ≥ 0.86 with a title score ≥ 0.90) — otherwise the player offers a “Search lyrics” picker |
| Lyric scroll | Centred auto-follow plus a word-by-word karaoke wipe; drag to browse, following resumes 5 s after the last drag, tap a line to seek; a plain un-timed sheet is shown as one list instead of being forced to scroll |
| Playback detail | Artwork, progress, transport and lyrics on one page: two columns in a wide window, stacked sections in a narrow one, so lyrics never need a mode switch |
| Custom covers | Per-song and per-playlist cover override from a local image |
| Playlists | Create / rename / add / remove / reorder / delete local playlists; “All local music” is an auto-maintained reserved playlist holding every scanned local song and cannot be renamed or deleted |
| Playback | Sequence / repeat-all / repeat-one / shuffle, with automatic skip-on-error and a reason for the failure (missing file, unsupported format, network failure and blocked cleartext each get their own message); DSD (`.dsf`/`.dff`) is decoded to PCM and played (only DST-compressed DSD cannot be decoded and is reported and skipped), and 192 kHz-and-above audio is converted to a rate the device accepts |
| Search | NetEase combined search (songs / albums / artists / playlists) plus local filtering |
| UI | Miuix 0.9.4 with the aurora gradient theme, dark/light/AMOLED, dynamic colour, cover-seeded colour; **the palette is desaturated and greyed overall** — every theme's accent / deep / glow / blush passes through `AuroraTheme.soft*` (`soften()`: Rec.601 luma desaturation by 34 %, then a 10 % mix towards `#7C8290`), and the schemes, gradients and artwork placeholders read only those four entries |

### Desktop-specific work

- **A real window** — 1180×780 and resizable; the player and database are released on close.
- **Keyboard shortcuts** — space toggles playback, `Ctrl+←/→` skip tracks, `Ctrl+↑/↓` nudge the volume by 5%, `Ctrl+M` mutes. The handler only runs for events no component consumed, so a space typed in the search field still inserts a space.
- **Adaptive-width layout** — from 840 dp up the main navigation moves from the bottom bar to a left-hand `NavigationRail` (the mini player stays at the bottom), and the playback detail page becomes two columns (artwork and transport left, lyrics right) from 900 dp up, falling back to stacked sections in a narrow window; lists stop at a 1120 dp reading width and centre themselves, and the wide playback detail page is capped and centred at that same width so the two pages agree where the content column is, instead of leaving a title and its row controls a thousand dp apart. The default window is 1180×780, so the wide layout is what you get on launch.
- **Lower saturation** — one `soften()` pass (Rec.601 luma desaturation by 34 %, then a 10 % mix towards `#7C8290`) sits behind all six themes. Numerically the contrast on a white surface *gains* (the weakest theme, Borealis, goes 2.54 → 3.14); on the dark surface it may dip slightly but stays above 4:1 (the widest dip is Borealis, 7.60 → 6.07). `AuroraPaletteTest` pins the transform, because reading the raw enum colours when building a gradient is the easy mistake — it would leave the gradients and the artwork placeholders glowing while only the colour schemes turned grey.
- **Right-click and a visible action button** — any song or collection row opens the same action sheet on right-click (`PointerMatcher.mouse(PointerButton.Secondary)`) as on long-press, and every row also carries a permanent `⋮` button, because neither right-click nor long-press is discoverable on its own. Long-press still works. The menu is the same `TrackActionsSheet` (favourite, queue, add to playlist, cover, import title, refresh lyrics, delete).
- **Native file dialogs** — folder and file pickers are Swing/AWT dialogs (with the system look and feel installed so a Windows chooser does not render in Metal); no Android runtime-permission dance at all.
- **Its own playback engine** — Java Sound plus SPI decoders (mp3spi / vorbisspi / jflac / JDK built-ins) in place of media3/ExoPlayer, feeding the audio device from one dedicated audio thread.
- **Native DSD decoding** — a pure-Kotlin DSF/DFF parser and half-band-FIR decimator (DSD64/128/256 → 176.4 kHz 16-bit PCM), registered as a Java Sound `AudioFileReader` through `META-INF/services`, so `AudioSystem.getAudioInputStream()` already hands back PCM and no playback code had to change.
- **Very high sample rates** — **anything above 192 kHz stops being offered to the device**: Windows mixers report support for rates they cannot actually render, so the file's own rate is only the first candidate when it is at or below 192 kHz; each candidate is really opened once and a refusing device falls through to 48 kHz / 44.1 kHz (the 176.4 kHz DSD output takes the same path) instead of failing the whole track. Ordinary files keep the original zero-conversion path.
- **On-disk media cache** — NetEase stream URLs expire in ~20 minutes and Java Sound cannot play a URL, so a track is downloaded on first play (keyed `<id>-<quality>`, 1 GB LRU cap). The payoff is instant seeking, no network stutter, and a prefetch of the next track.
- **Volume slider** — in the player pane and in Settings, applied live while playing (the engine is not restarted).
- **Cache management** — Settings shows the cache size and clears it in one click.
- **Online lyric completion** — when no local `.lrc` exists, the song title and artist are sent to NetEase (eapi, up to 15 hits) and LRCLIB (indexed by name and duration, no login or account needed) in parallel, and the local ranker scores each hit on title / artist / duration. Only a hit above the threshold is imported automatically; everything else is listed in the player (title, artist · album, duration, source) for one-tap import. A song is not asked for twice within 10 minutes, and an offline failure can be retried.
- **A real browser for sign-in** — there is no WebView on the desktop, so sign-in opens the system browser and the session is imported by pasting the cookie — which gets 2FA, captcha and “already signed in on this PC” for free.

### Build and run

Toolchain: JDK 21, Gradle 9.8.0, Kotlin 2.4.20, Compose Multiplatform 1.12.1, Miuix 0.9.4.

```bat
:: the launcher lives in D:\AI Project\.toolchain\buildwin.cmd and must be called from that directory
cmd /c "buildwin.cmd :run"                 :: run it
cmd /c "buildwin.cmd :test"                :: unit tests
cmd /c "buildwin.cmd :createDistributable" :: runnable application image
cmd /c "buildwin.cmd :packageMsi"          :: MSI installer
```

`createDistributable` writes to `build\compose\binaries\main\app\HexNC\` (**221.7 MB**, self-contained
runtime); run `HexNC.exe` from there and no local Java installation is needed. `packageMsi` writes
`build\compose\binaries\main\msi\HexNC-1.1.0.msi` (**106.5 MB**), a copy of which sits in the project
root. WiX does not have to be installed: the plugin downloads `wix311-binaries.zip` into the Gradle
cache by itself (the first build spends a few extra minutes on it), and it ends up extracted in
`build\wix311\` (`candle.exe` / `light.exe`).

### Windows install, overwrite-install and uninstall

After jpackage has produced the MSI, `packageMsi` runs **`packaging\windows\patch-msi.ps1`** (a
`doLast` hook in `build.gradle.kts`; the build fails if the patch fails) and edits three things in the
compiled MSI database through the `WindowsInstaller.Installer` COM API, inside one transaction:

| Edit | Why |
| --- | --- |
| `Property.ProductCode` → a freshly generated GUID per build | jpackage derives the ProductCode from package name + version only, so every rebuilt 1.0.0 shipped **the same ProductCode as the installed 1.0.0**: Windows Installer classified it as a maintenance run of the same product and `msiexec /i` returned 0 while **replacing nothing** (measured: 458 files / 232,623,561 B and both sha256 identical before and after; the log says `Skipping RemoveExistingProducts action: current configuration is maintenance mode or an uninstall`). With a new ProductCode and the UpgradeCode unchanged, the installer sees an older product of the same family and `RemoveExistingProducts` performs remove-then-install |
| `Upgrade` row `JP_UPGRADABLE_FOUND`, Attributes `257 → 769` (adds `VersionMaxInclusive`) | jpackage's template compiles its own-version row with `IncludeMaximum="no"`, so an equal version is neither older nor newer, matches neither Upgrade row and is never removed; with that bit set "≤ the current version" actually matches. The `JP_DOWNGRADABLE_FOUND` row (Attributes 1) is deliberately untouched, but that table only *detects* and jpackage emits no blocking action in this package, so **a downgrade is not blocked either** (measured — see below) |
| `Shortcut` gains an `Uninstall HexNC` row | The Start menu only had "HexNC", leaving uninstall to Settings → Apps. The link runs `[SystemFolder]msiexec.exe /x [ProductCode]` and lives on the component that already owns the Start-menu shortcut (MSI SQL cannot even name `Registry.Key`, so no new registry-keypath component can be authored), so it appears exactly when the application shortcut does |

**Uninstall**: Start menu → “HexNC” → “**Uninstall HexNC**”, or Settings → Apps → Installed apps →
HexNC → Uninstall, or `msiexec /x {ProductCode}`.

**Overwrite install**: just run the newer MSI (double-click, or `msiexec /i`); it removes the older
install first and leaves a single Apps entry — no side-by-side copies, no abort. Running the identical
MSI twice is a maintenance no-op (exit 0, files untouched); **a downgrade is not blocked either** —
installing the 1.0.0 MSI over an installed 1.1.0 returned 0 and really did swap the app back to 1.0.0
(the `JP_DOWNGRADABLE_FOUND` row only detects, and this MSI carries no blocking action). In other words:
whichever MSI you run is the version you end up with.

Measured on this machine (non-elevated, `/qn`): a fresh 1.0.0 install → Apps entry `HexNC 1.0.0`
(`{AB338083-…}`); then 1.1.0 → exit 0, the only entry is `HexNC 1.1.0` (`{3B0E62BC-…}`),
`HexNC-For-Windows-1.1.0-*.jar` replaces the 1.0.0 jar and `HexNC.exe`'s sha256 goes from
`8E4B71F0…` to `27B65288…`; downgrading back to 1.0.0 → exit 0, entry back to `HexNC 1.0.0`; running
the same 1.1.0 MSI again → exit 0, maintenance no-op; `msiexec /x {3B0E62BC-…}` → exit 0 and the Apps
entry, `%LOCALAPPDATA%\HexNC`, both Start-menu shortcuts and the desktop shortcut are gone, with
nothing left in `D:\Config.Msi`.

One caveat: re-run or repair with the MSI from **the same path and file name** — a maintenance run
against a renamed copy in another directory cannot resolve its own source package and fails with 1603
(that is stock Windows Installer behaviour, not the patch; it was hit once while measuring).

**Not yet proven**: `packageExe` (the Exe bundle embeds its own MSI and **is not patched**), and
whether an overwrite-install of a *rebuilt* same-version MSI (same version, different ProductCode)
also removes-then-installs — only "the identical file run twice is a no-op" was measured.
`RemoveExistingProducts` also stays before `CostInitialize` (stock jpackage ordering), so a failed new
install does not roll the old product back.

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

**180 tests in 19 classes, all passing** (`cmd /c "buildwin.cmd :test"` — measured: 0 failures,
0 errors): the table above lists each class. The suite is the same source set as the Android build;
the only rewrite is `PlaybackMessageTest`, because media3 is gone and the playback error codes now
come from `playback.PlaybackErrorCode` (ExoPlayer's numbering: 2001 network failed, 2002 timeout,
2004 bad HTTP status, 2005 file not found, 2006 no permission, 2007 cleartext blocked, 3001 decoding
failed). What the test pins is the error-code → user-visible-message mapping. The three classes added
by this sync are `data.lyrics.LyricMatchTest` (title / artist / duration scoring and the auto-import
bar), `data.lyrics.LrclibJsonTest` (LRCLIB response parsing) and `data.local.DsdSourceTest` (DSD
extension detection), alongside nine new cases in `LrcParserTest` (plain text), `IdsTest` (the
reserved playlist id) and `LyricsTest` (the un-timed sheet).

The DSD work added three more classes: `data.local.dsd.DsdDecodeTest` (11 cases — parsing hand-built
DSF/DFF containers, a first-order sigma-delta sine surviving the half-band decimation, stereo channel
separation, DST and malformed files rejected, the half-band taps and the decimation factor),
`playback.AudioOutputFormatTest` (5 cases pinning the "never offer more than 192 kHz" ladder, with a
176.4 kHz stream still falling back to 48 kHz / 44.1 kHz) and `playback.DsdServiceProviderTest`
(2 cases that really open a temporary `.dsf` through `AudioSystem.getAudioFileFormat()` to prove the
SPI registration yields 176.4 kHz PCM, and that non-DSD files still reach the JDK readers).

The palette work is guarded by `ui.theme.AuroraPaletteTest` (4 cases, the very same file as in the
Android tree): colour tuning is the kind of change that is easy to get wrong and impossible to see in a
headless build — reading the raw enum colour while building a gradient leaves the gradients and the
artwork placeholders neon while only the schemes turn grey — and it is a pure numeric transform, so the
assertions pin that all four stops of all six themes come out less saturated, that white-surface
contrast never drops and dark-surface contrast stays above 4:1, and that `gradientStops` is built from
the `soft*` entries.

### Known limitations

- **Build path** — the `createDistributable` image runs as-is and `packageMsi` wires WiX up by itself; the only prerequisite is reachable Maven Central and GitHub (WiX is fetched on the first build).
- **Codecs** — Java Sound decodes MP3 (mp3spi), FLAC (jflac), OGG (vorbisspi) and WAV/AIFF/AU (JDK). Every NetEase tier is covered (`standard`/`higher`/`exhigh` are MP3, `lossless`/`hires` are FLAC), but **m4a / aac / opus / wma / ape files in a local library have no decoder** and are skipped. **DSD (`.dsf` / `.dff`)** now plays: the built-in pure-Kotlin decoder parses the DSF and DFF containers and decimates DSD64 / DSD128 / DSD256 to 176.4 kHz 16-bit PCM for Java Sound — only **DST-compressed** DSD and structurally broken files cannot be decoded and are reported before playback instead of surfacing as a decoding error. **192 kHz-and-above** PCM is never handed to the device at its own rate; it is converted to a rate the device accepts (48 kHz / 44.1 kHz) first.
- **Online lyrics** — completion depends on NetEase or LRCLIB actually cataloguing the track and on a working network; NetEase search is rate-limited and risk-controlled, so a given song is only asked about once per 10 minutes and a failure can be retried. Requests contain only title / artist / album text — never file contents, paths or account data (LRCLIB needs no login).
- **Favourited NetEase albums** — `/api/album/sublist` has never been called against a real signed-in account here (this machine cannot get past NetEase's risk control), so only the request shape, the `api` → `eapi` fallback and the "a failed read keeps the previous list instead of blanking it" behaviour were verified; whether the album list matches the account, and whether importing a whole album on tap is what you want, can only be confirmed on a real machine. The "Recently played" shelf added in the same batch is the same story: the record point sits in `startAt()` (a track that was opened is recorded even if its download then failed), that ordering has no test coverage, and only the re-ordering logic in `recentlyPlayed()` (id list order) is pinned.
- **Lower-saturation colours** — only the numbers are verified (`AuroraPaletteTest`'s four assertions plus the per-theme contrast arithmetic); whether the result actually looks better is a human judgement, and there is no display attached to this machine, so there is no screenshot evidence either.
- **Shell integration** — no taskbar transport controls, no global media keys, no SMTC, no tray icon. Android had none of these either; they could be added later.
- **Crossfade** — kept in Settings for parity with Android; the desktop engine does not implement it yet.
- **UI tests** — the adaptive layout, the left navigation rail, the right-click menu and the `⋮` buttons are only confirmed by compilation and manual runs; there are no UI automation tests (Compose UI testing is not wired into `:test`).
- **Installer** — the MSI's install, overwrite-install (1.1.0 over 1.0.0), downgrade install (1.0.0 over 1.1.0, which really does install the older version), identical-file re-run and uninstall paths were all measured on this machine (see “Windows install, overwrite-install and uninstall”); `packageExe` is not patched, and an overwrite-install of a rebuilt same-version MSI was not measured.
