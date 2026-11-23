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
| 歌单同步 | 登录后拉取「我喜欢的音乐」与自建/收藏歌单；歌单页里有一条独立的**「网易云专辑」**入口，点进去是收藏专辑的整页列表（`/api/album/sublist`，`api` 失败时回退 `eapi`），点按才按需导入整张专辑——收藏专辑本身不写曲库。这一页有明确的「未登录 / 加载中 / 加载失败可重试 / 空列表」四种状态，网络失败会显示出来而不是安静地空着。**2026-11 修掉了一个让收藏专辑永远是空的解析错误**：专辑数组实际在响应顶层的 `data` 键下，模型却声明成 `albums`，于是永远解出空列表并把空结果写进缓存；改用 `@SerialName("data")` 后，用真实登录账号实测拿到 46 张、`limit=20` 时 `more=true` |
| 曲库 | 曲库首页是「入口」而不是「堆货架」：统计卡下方是浏览入口（全部歌曲 / 专辑 / 我喜欢的音乐 / 最近添加 / 最近播放），每个入口打开自己的二级页面；艺术家分区已移除。首页只留**「最近播放」**（按播放顺序保存最近 24 首，存在偏好设置里，不新建数据库表）与**「最近添加」**（只列歌曲、只给 4 条，按加入时间倒序）两个货架，都带「查看全部」 |
| 本地导入 | 扫描 `音乐` 文件夹、自选文件夹、自选文件（音频 + 同名 `.lrc` 歌词），也可在设置页单独为本地歌曲扫描歌词。歌词文件的读取统一走 `decodeLyricBytes()`：BOM 优先，其次按 NUL 模式识别无 BOM 的 UTF-16，再严格校验 UTF-8，然后回退 GB18030，最后宽松 UTF-8——中文 `.lrc` 大多是 GBK，过去按 UTF-8 读会抛出或变成乱码，于是"本地歌词导入不进来" |
| 歌词 | LRC / 逐字（yrc）/ 翻译 / 罗马音解析与排版，可调字号、行距、显示项；本地找不到歌词时自动到网易云与 LRCLIB 搜索，只有匹配度足够高（综合 ≥ 0.86 且标题 ≥ 0.90）才自动导入，否则在播放页提供「在线搜索」面板由你挑选。歌曲 / 专辑 / 歌手 / 搜索结果的右键菜单里「导入本地歌词」都接的是同一套选择器，并且会先清掉"这首歌的歌词被删过"的墓碑标记，否则导入会安静地失败 |
| 歌词滚动 | 居中自动跟随 + 逐字卡拉 OK 渐变擦除，可手动拖动、拖动后 5 秒恢复跟随、点行跳转；只有纯文本歌词（无时间轴）时改为整篇列表显示，不做假滚动 |
| 播放详情页 | 封面、传输控制与歌词同屏：宽窗口左右分栏，窄窗口上下分区，歌词不再需要来回切换。**进度条沉到页面最底部**，细长一条（3 dp 高、两端 2 dp 圆角），上方一行等宽时间，拖动只更新显示、松手才 seek；右上角第三个按钮（`i`）打开**歌曲详情**面板，列出专辑、艺术家、音轨号、时长、来源、**实际授予的音质**、格式、文件大小、有无歌词、加入时间与文件路径；第四个按钮（`⋮`）打开**播放选项**，桌面端把音量滑杆收进这里，并提供**沉浸模式**入口 |
| 歌手详情 | 除本地歌曲外，另起一段列出该歌手在网易云发布的专辑（有 `id` 时走 `/api/artist/albums/<id>`，只有名字时退回专辑搜索并按歌手名过滤），点按即导入整张专辑；即使本地一首歌都没有，歌手页也能从搜索直接打开 |
| 自定义封面 | 歌曲与歌单可指定本地图片作封面 |
| Playlist | 本地歌单新建 / 改名 / 增删歌 / 删除 / 排序；「全部本地音乐」是自动维护的保留歌单，汇总所有扫描到的本地歌曲，不支持改名或删除 |
| 搜索 | 网易云综合搜索（歌曲 / 专辑 / 歌手 / 歌单）与本地库过滤 |
| 播放 | 顺序 / 列表循环 / 单曲循环 / 随机，播放失败自动跳过并说明原因（文件不支持、解码失败、设备失败、文件不可读、网络失败、明文被拦截各有独立提示）；DSD（`.dsf`/`.dff`）先解码成 PCM 再播放（DST 压缩的 DSD 无法解码，会明确提示并跳过），192 kHz 及以上会按设备支持的采样率转换后播放 |
| 音质 | 播放默认向网易云请求 **Hi-Res** 档，并在右上角 `i` 歌曲详情面板里按服务端**实际授予**的档位显示音质与真实格式（如 `FLAC · 1039 kbps`；该曲目没有无损版权时追加「无无损版权」），播放页本身不再挂胶囊——不会把 320k MP3 说成母带。输出位深不再无条件压到 16-bit：源是 24-bit 且设备接受时按 `PCM_SIGNED 24` 直通；只有必须降到 16-bit 时才加 **TPDF dither**（±1 LSB 三角噪声）而不是直接截断；源采样率与设备一致就零转换直通，否则走 **48-tap Hann 窗多相 sinc** 重采样（256 相位表，截止取两端 Nyquist 较小者）；mono 转双声道是同电平复制（不会 +3 dB 或翻倍）；输出前统一限幅，dither/重采样都不会溢出成爆音 |
| 下载 | 网易云歌曲可下载为**真实文件**：单曲行菜单里有「下载」，歌单 / 专辑 / 歌手页有「全部下载」。下载音质默认也是 **Hi-Res**，取到的是该曲目与账号允许的最高档；**按服务端实际返回的容器决定扩展名**（返回 FLAC 就存 `.flac`，绝不把 flac 改名成 mp3）。MP3 写 ID3v2.3 标签（标题 / 艺术家 / 专辑 / 音轨号 / 碟号 / 年份 / 内嵌封面，UTF-16 文本帧），FLAC 写原生 Vorbis comment + PICTURE 块（音频帧逐字节不变，padding 足够时文件大小都不变），同名 `.lrc` 歌词一并写出；无版权或该音质不可用时给出明确原因而不是留个空文件；同批重名自动加 `(2)`，已存在的文件会跳过；下载中断只留 `.part` 并被清理 |
| 格式支持 | 扫描器收 14 种扩展名，但**能不能解码是要被检查的**：mp3 / flac / wav / ogg / m4a / aac / alac / ape / dsf / dff 由内置 SPI 解码；opus / wma 与 DST 压缩的 DSD 在桌面端确实没有解码器，这类歌曲在列表里带「不支持」徽标、点播时给出明确提示，而不是播到一半抛一个笼统的解码错误。判断方式是「文件头魔数 + 扩展名」并带 512 条 LRU 缓存（`DecodeCapability`），因此改了扩展名的 opus、藏在 `.ogg` 里的 opus、无扩展名的 ASF 都能认出来。完整矩阵见 [`format-matrix.md`](format-matrix.md) |
| 界面 | Miuix 0.9.4 + 极光渐变主题，深色 / 浅色 / AMOLED、动态取色、跟随封面取色；共 **九套主题**，除原有六套外新增 **莫奈 / 毛玻璃 / 暗色纯黑白**（后两者分别带 `glassy` 与 `alwaysDark` 标记）；**整体降饱和、加入灰度**——每套主题的 accent / deep / glow / blush 都只经过 `AuroraTheme.soft*`（`soften()`：按 Rec.601 亮度去饱和 34%，再与 `#7C8290` 混 10%），配色方案、渐变与封面占位图都读这四个入口。列表改成**扁平行 + 细分割线**（封面 48 dp、圆角 10 dp、行高 64 dp、底色透明），更接近椒盐音乐与网易云原版的列表观感；尺寸、圆角、字阶与动效时长集中在新加的 `ui/theme/Tokens.kt`（`Spacing` / `Radii` / `RowMetrics` / `TypeScale` / `Motion`），改一处就两端一致 |

### 桌面端优化

- **真正的窗口**：1180×780 可缩放，`Window` + Compose Desktop，关闭时优雅释放播放器与数据库。
- **键盘快捷键**：空格 播放/暂停，`Ctrl+←/→` 上一首/下一首，`Ctrl+↑/↓` 音量 ±5%，`Ctrl+M` 静音。事件只在组件未消费时才处理，因此在搜索框里按空格仍然是输入空格。另外，在播放详情页按空格曾经会让窗口直接崩溃：按键在 `Window.onKeyEvent` 之前被聚焦的控件先消费，播放页会把"打开播放页"再执行一次，于是同一个路由被压入返回栈两次，Miuix 抛出 `Duplicate contentKey on the back stack: NowPlaying`（`NavDisplay.kt:381`，抛在组合帧里，整个窗口随之退出）。修法是 `navigate` 忽略与栈顶相同的路由，而不是依赖调用方去重。
- **自适应宽度布局**：窗口宽度 ≥ 840 dp 时主导航从底部移到左侧 `NavigationRail`（迷你播放条仍在底部），播放详情页 ≥ 900 dp 时变成「左封面与控制、右歌词」的分栏，窄窗口自动回到上下分区；列表在超宽窗口下限制到 1120 dp 阅读宽度并居中，播放详情页的宽布局也按同一宽度居中，免得标题和行尾按钮相隔上千 dp，也让播放页与列表页的内容列对齐。默认窗口 1180×780，打开就是宽屏布局。
- **降低饱和度**：整体配色统一走一遍 `soften()`（Rec.601 亮度去饱和 34% + 与 `#7C8290` 混 10%），九套主题全部覆盖；数值上白底对比度不降反升（最弱的北境主题 2.54 → 3.14），深色底允许小幅下降但都还在 4:1 以上（最大降幅北境 7.60 → 6.07）。`AuroraPaletteTest` 把这个变换钉住，因为"顺手读原色"是最容易犯的错——那会让渐变和封面占位图继续发光，只有配色方案变灰。
- **右键与常驻操作按钮**：任意歌曲行、歌单行都能右键唤出与长按相同的操作菜单（`PointerMatcher.mouse(PointerButton.Secondary)`），每行右侧另外有一个常驻 `⋮` 按钮——右键和长按都不足以被自己发现，所以才补了这个可见入口；长按本身保留。菜单内容仍是同一套 `TrackActionsSheet`（收藏、加入队列、加入歌单、换封面、导入标题、刷新歌词、删除）。
- **原生文件对话框**：文件夹/文件选择走 Swing/AWT 原生对话框（并安装系统 Look and Feel，不让 Windows 上出现 Metal 风格的选择框），没有 Android 的运行时存储权限流程。
- **自带播放引擎**：用 Java Sound + SPI 解码器（mp3spi / vorbisspi / jflac / JDK 内置）替代 media3/ExoPlayer，在专用音频线程上推流。
- **DSD 原生解码**：内置纯 Kotlin 的 DSF / DFF 解析与半带 FIR 抽取（DSD64/128/256 → 176.4 kHz 16-bit PCM），并按 Java Sound SPI 注册为 `AudioFileReader`（`META-INF/services`），所以 `AudioSystem.getAudioInputStream()` 拿到的就已经是 PCM，播放层代码完全不用改。
- **超高采样率兼容**：**超过 192 kHz 的采样率不再交给设备**——Windows 的混音器会对它其实渲染不了的采样率返回「支持」，所以文件自身的采样率只在不高于 192 kHz 时才作为首选，否则直接从目标采样率开始；候选格式会逐个真正 `open` 一次，设备拒绝就依次退到 48 kHz / 44.1 kHz（DSD 解码出的 176.4 kHz 也走这条路），而不是整曲报错。普通文件仍走原来的零转换路径。
- **在线歌曲落盘缓存**：网易云直链约 20 分钟过期且不能直接给 Java Sound 播，因此首次播放即下载到本地（`<键>-<音质>` 缓存，1 GB LRU 上限），换来的好处是拖动进度秒响应、无网络卡顿，并会在开播下一首前预取。
- **音量滑杆**：桌面端的音量滑杆已收进播放页右上角 `⋮` 播放选项弹层（与静音切换并列，播放中实时生效、不重启引擎），设置页仍保留一条，播放页因此只剩封面、标题与传输键。
- **缓存管理**：设置页显示媒体缓存占用并可一键清空。
- **歌词在线补全**：本地扫描没有找到 `.lrc` 时，用「歌名 + 歌手」并发查询网易云（eapi，最多 15 条）与 LRCLIB（按名称与时长索引，不需要登录、不需要账号），在本地按标题 / 歌手 / 时长打分排序；达到门槛才自动写入，否则在播放页列出候选（歌名、歌手 · 专辑、时长、来源），点一下即导入。同一首歌 10 分钟内不会重复请求，离线时失败可重试。
- **登录用真浏览器**：桌面没有 WebView，直接用系统浏览器登录网易云，再粘贴 Cookie 导入 —— 2FA、验证码、以及「本机浏览器已登录」都能用上。
- **沉浸播放页**：封面作为背景（同一张图模糊 72 dp、20% 透明度，上面压两层主题渐变压住对比度），换歌时用 320 ms `Crossfade` 过渡；封面尺寸随窗口长——宽布局 380 dp、窄布局取 62% 宽度封顶 320 dp（原来固定 268 dp，在大窗口里像缩略图）。封面圆角、标题字阶、艺术家字重、传输键尺寸统一走 `Radii` / `TypeScale`。另有一个真正全屏的**沉浸模式**（`⋮` → 沉浸模式）：只留封面与歌词，封面在上、滚动歌词在下，顶栏与传输控制全部隐去，轻点封面退出。
- **骨架屏而不是转圈**：列表加载时铺 7 行占位（48 dp 灰块 + 62% / 38% 两条灰条），一条 1200 ms 的横向微光扫过；首页与二级列表在冷启动时不再是一块空白加一个 `InfiniteProgressIndicator`。
- **迷你播放条**：进度线 3 dp → 2 dp（3 dp 读起来像分割线而不是播放状态），行高 64 dp，封面作 20% 透明度的模糊底色，上面压 72% 背景遮罩，保证行内文字对比度仍在 4.5:1 以上。
- **等宽时间数字**：播放页的已播 / 总时长用 `fontFeatureSettings = "tnum"`，否则每秒进位的比例数字会让整行横向抖一下。
- **沉底细长进度条**：播放页的 seek 条固定在内容区底部（`PROGRESS_DOCK_HEIGHT` 48 dp 为它预留空档），3 dp 高、两端 2 dp 圆角；拖动时只更新显示、`onDragEnd` 才真正 seek，避免拖动过程里反复触发解码与定位。
- **下载落盘**：单曲菜单与歌单 / 专辑 / 歌手页的「全部下载」把网易云的直链存成真实音频文件，默认落在 `%USERPROFILE%\Music\HexNC`（设置里可改），文件名按 `艺术家/专辑/音轨号 - 标题.ext` 组织，非法字符会被替换；下载器是最多 2 个并发的协程池，同一批重名自动加 `(2)`，MP3 写 ID3v2.3 标签（含内嵌封面），并写出同名 `.lrc`。进度面板显示完成 / 跳过 / 失败三类计数，并汇总这批实际落盘的容器（如 `FLAC ×2 · MP3 ×1`）与其中多少首没有无损版权，可取消、可只重试失败项；下载与媒体缓存是两套东西——缓存是播放用的临时副本（1 GB LRU、文件名是 sha1、没有扩展名），下载是用户资产，不会被清缓存删掉。
- **能覆盖安装、也能卸载**：安装包每次构建换一个 ProductCode（jpackage 原来只按包名 + 版本推导，重建的 1.0.0 会带着已安装那个 1.0.0 的同一份 ProductCode，于是 `msiexec /i` 返回 0 却什么都不换），所以现在把新 MSI 装到旧版上会先删后装、只留一个「应用」条目；开始菜单里也多了一个「Uninstall HexNC」。细节与实测记录见「Windows 安装、覆盖安装与卸载」。
- **自带图标**：桌面端此前**根本没有 .ico**——jpackage 与 `Window` 都没配图标，所以任务栏、Alt-Tab 与窗口一直显示 Java 的咖啡杯。现在 `packaging\windows\hexnc.ico`（16/20/24/32/40/48/64/128/256 九种尺寸）由 `iconFile` 交给 jpackage 编进 `HexNC.exe`，窗口本身用 `src\main\resources\hexnc.png`（`Main.appIcon()`）设为 `Window(icon = …)`，两处是同一张图，并且和 Android 的启动器图标完全一致。加载失败只返回 `null`，不会拦住启动。

### 构建与运行

工具链：JDK 21、Gradle 9.8.0、Kotlin 2.4.20、Compose Multiplatform 1.12.1、Miuix 0.9.4。

```bat
:: 构建脚本在 D:\AI Project\.toolchain\buildwin.cmd，需要在该目录下调用
cmd /c "buildwin.cmd :run"                 :: 直接运行
cmd /c "buildwin.cmd :test"                :: 跑单元测试
cmd /c "buildwin.cmd :createDistributable" :: 生成可运行的应用目录
cmd /c "buildwin.cmd :packageMsi"          :: 生成 MSI 安装包
```

`createDistributable` 的产物在 `build\compose\binaries\main\app\HexNC\`（**223.5 MB**，内置完整运行时），双击其中的 `HexNC.exe` 即可运行，不需要本机装 Java；`packageMsi` 产出的安装包是 `build\compose\binaries\main\msi\HexNC-1.2.0.msi`（**108.0 MB**），也已复制一份到工程根目录。WiX 不需要自己装：插件会自行下载 `wix311-binaries.zip` 到 Gradle 缓存（第一次构建时多花几分钟），解开后在 `build\wix311\`（`candle.exe` / `light.exe`）。

### Windows 安装、覆盖安装与卸载

`packageMsi` 在 jpackage 出包之后会再跑一遍 **`packaging\windows\patch-msi.ps1`**（`build.gradle.kts` 里的 `doLast` 钩子，补丁失败即构建失败），用 `WindowsInstaller.Installer` COM 接口在事务里改 MSI 数据库的三处：

| 改动 | 为什么 |
| --- | --- |
| `Property.ProductCode` → 每次构建新生成一个 GUID | jpackage 只按「包名 + 版本」推导 ProductCode，所以重新构建的同一个版本都带着**已安装那一个的同一份 ProductCode**（最明显的就是拿重建的 1.0.0 去装已装的 1.0.0）：Windows Installer 把它当成同一个产品的维护模式，`msiexec /i` 返回 0 却**什么都没换**（实测安装前后都是 458 个文件 / 232,623,561 B，两个 sha256 完全一样，日志写着 `Skipping RemoveExistingProducts action: current configuration is maintenance mode or an uninstall`）。换成新 ProductCode、UpgradeCode 不变，安装程序就会把它当成同族里的旧产品，走 `RemoveExistingProducts` 先删后装 |
| `Upgrade` 里 `JP_UPGRADABLE_FOUND` 那行 Attributes `257 → 769`（加 `VersionMaxInclusive`） | jpackage 的模板给自己版本那行编译的是 `IncludeMaximum="no"`，于是同版本既不是「更旧」也不是「更新」，两行都不匹配、永远不会被移除；补上这一位后「≤ 当前版本」才真的匹配。`JP_DOWNGRADABLE_FOUND` 那行（Attributes 1）故意不动，而这张表只负责**探测**、jpackage 在这个包里没有生成任何拦截动作，所以**降级同样不会被拦**（实测见下） |
| `Shortcut` 增一行 `Uninstall HexNC` | 开始菜单原来只有「HexNC」，卸载只能去「设置 → 应用」里找。新链接指向 `[SystemFolder]msiexec.exe /x [ProductCode]`，挂在原来那个开始菜单快捷方式的组件上（MSI SQL 连 `Registry.Key` 列都不能引用，所以无法新建注册表键路径的组件），因此它跟应用快捷方式同生共死 |

**卸载入口**：开始菜单 →「HexNC」→「**Uninstall HexNC**」；或 设置 → 应用 → 已安装的应用 → HexNC → 卸载；或 `msiexec /x {ProductCode}`。

**覆盖安装**：直接双击新版 MSI（或 `msiexec /i`）即可，它会把旧版先卸掉再装，只留一个「应用」条目，不会出现两个并排的安装。同一个 MSI 重复运行只是维护模式空转（返回 0，文件不动）；**降级也不会被拦** —— 把 1.0.0 的 MSI 装到已装的 1.1.0 上实测返回 0，并且真的把应用换回了 1.0.0（`JP_DOWNGRADABLE_FOUND` 那行只做探测，这个 MSI 里没有对应的拦截动作）。换句话说：装上哪个版本，取决于你运行的是哪个 MSI。

实测（本机、非提权、`/qn`）：1.0.0 全新安装 → 应用条目 `HexNC 1.0.0`（`{AB338083-…}`）；再装 1.1.0 → 返回 0，条目只剩 `HexNC 1.1.0`（`{3B0E62BC-…}`），`HexNC-For-Windows-1.1.0-*.jar` 取代 1.0.0 的 jar，`HexNC.exe` 的 sha256 由 `8E4B71F0…` 变为 `27B65288…`；**再装 1.2.0 → 返回 0，条目只剩 `HexNC 1.2.0`（`{7AE80026-…}`），jar 换成 `HexNC-For-Windows-1.2.0-*.jar`，`HexNC.exe` 的 sha256 变为 `054E8EFA…`，开始菜单两个链接与桌面快捷方式都还在**；1.1.0 装回 1.0.0（降级）→ 返回 0，条目变回 `HexNC 1.0.0`；同一个 1.2.0 MSI 再跑一次 → 返回 0、维护模式空转（条目、文件、快捷方式均不变）；`msiexec /x {7AE80026-…}` → 返回 0，应用条目、`%LOCALAPPDATA%\HexNC`、开始菜单与桌面快捷方式全部消失，`D:\Config.Msi` 无残留。

注意：重装/修复请**用同一个路径与文件名**的 MSI —— 换个目录或改个名字再运行维护模式，Windows Installer 找不到它自己的源包，会以 1603 失败（这与补丁无关，原生 jpackage MSI 同样如此，本次实测踩到过一次）。

**仍未验证**：`packageExe`（Exe 安装包内嵌自己的 MSI，**不经过这个补丁**）；重新构建过的**同版本** MSI（ProductCode 与已装的同一个版本不同）覆盖安装时会不会同样先删后装，只有「同一个文件重复运行 = 空转」这一条实测过。另外 `RemoveExistingProducts` 仍保持在 `CostInitialize` 之前（沿用 jpackage 的顺序），所以新安装失败时不会回滚旧产品。

应用目录里的运行时**包含全部 JDK 模块**（`includeAllModules = true`）。这不是浪费：jlink 只会打包它能从类路径推断出的模块，而 JDBC 兼容层调用的是 `java.sql.DriverManager`，第一次打包出来的应用启动即崩溃并抛出 `NoClassDefFoundError: java/sql/DriverManager`（`:run` 用的是完整工具链 JDK，所以看不出这个问题）。多带约 60 MB 换掉这一整类故障。

**打包版是 GUI 程序，没有控制台**：如果启动时抛异常，窗口会直接消失。因此 `main()` 安装了默认未捕获异常处理器，把堆栈追加到 `%APPDATA%\HexNC\crash.log` 并弹窗告知路径。

首次运行会在 `%APPDATA%\HexNC` 下创建 `hexnc.db`、`prefs\`、`covers\`、`media\`。

### 测试

**294 个测试 / 32 个测试类，全部通过**（`cmd /c "buildwin.cmd :test"`，实测 0 失败 0 错误 0 跳过）：

| 测试类 | 数量 |
| --- | --- |
| `data.lyrics.LyricMatchTest` | 24 |
| `data.local.LrcParserTest` | 21 |
| `data.model.IdsTest` | 12 |
| `data.netease.NeteaseCryptoTest` | 12 |
| `data.netease.NeteaseLoginGateTest` | 12 |
| `data.local.dsd.DsdDecodeTest` | 11 |
| `data.download.Id3v2Test` | 11 |
| `data.download.FlacTagsTest` | 12 |
| `data.model.LyricProgressTest` | 11 |
| `data.model.LyricsTest` | 11 |
| `data.netease.NeteaseJsonTest` | 11 |
| `playback.DecodeCapabilityTest` | 11 |
| `playback.AudioStreamOpenTest` | 3 |
| `playback.PcmConverterTest` | 11 |
| `data.download.DownloadPathsTest` | 10 |
| `playback.PlaybackMessageTest` | 10 |
| `playback.PcmQuantizeTest` | 9 |
| `data.local.LyricDecodeTest` | 8 |
| `data.lyrics.LrclibJsonTest` | 8 |
| `data.netease.StreamUrlsTest` | 8 |
| `data.repo.NeteaseAlbumPagingTest` | 8 |
| `data.repo.GrantedQualityTest` | 7 |
| `playback.PcmOutputPlanTest` | 8 |
| `data.netease.NeteasePlaylistIdsTest` | 7 |
| `data.netease.RequestIdTest` | 7 |
| `playback.SincResamplerTest` | 6 |
| `data.local.DsdSourceTest` | 5 |
| `data.model.LyricSubLineTest` | 5 |
| `playback.AudioOutputFormatTest` | 5 |
| `ui.theme.AuroraPaletteTest` | 4 |
| `data.netease.NeteaseEndpointTest` | 3 |
| `playback.DsdServiceProviderTest` | 2 |

测试与 Android 版共用同一套源码；唯一改写的是 `PlaybackMessageTest`：media3 被移出后，播放错误码由 `playback.PlaybackErrorCode` 提供（沿用 ExoPlayer 的取值 2001 网络失败 / 2002 超时 / 2004 HTTP 状态码错误 / 2005 文件不存在 / 2006 无权限 / 2007 明文被拦截 / 3001 解码失败），被测试锁住的是「错误码 → 用户可见提示」的映射本身。本次同步新增的三类测试是 `data.lyrics.LyricMatchTest`（标题 / 歌手 / 时长打分与自动导入门槛）、`data.lyrics.LrclibJsonTest`（LRCLIB 响应解析）与 `data.local.DsdSourceTest`（DSD 扩展名识别），另有 `LrcParserTest` 的纯文本歌词、`IdsTest` 的保留歌单 id、`LyricsTest` 的无时间轴判定共九例新增。

DSD 支持带来的三类新测试是 `data.local.dsd.DsdDecodeTest`（11 例：自造 DSF / DFF 容器解析、一阶 sigma-delta 正弦经半带抽取后仍然存在、双声道分离、DST 与假文件被拒绝、半带滤波器系数与抽取倍数）、`playback.AudioOutputFormatTest`（5 例：锁住「超过 192 kHz 绝不作为候选」的采样率阶梯，以及 176.4 kHz 输出仍可回退到 48 kHz / 44.1 kHz）与 `playback.DsdServiceProviderTest`（2 例：真的用 `AudioSystem.getAudioFileFormat()` 打开一个临时 `.dsf`，确认 SPI 注册生效能拿到 176.4 kHz PCM；同时确认非 DSD 文件仍由 JDK 自带解码器处理）。

降饱和那一次改动的护栏是 `ui.theme.AuroraPaletteTest`（4 例，与 Android 版逐字节相同的同一份文件）：颜色调校是最容易改错又看不见的一类改动（构造渐变时顺手读原色，配色方案变灰而渐变还是霓虹色），而它本身是纯数值函数，所以把「四个色位每一支都必须比原色更不饱和」「白底对比度不许下降、深色底允许小幅下降但必须 ≥ 4:1」「`gradientStops` 必须由 `soft*` 入口构造」三类断言钉进测试。

这一轮新增的五组测试各自钉住一个容易悄悄退化的地方：

- `playback.DecodeCapabilityTest`（11 例）：opus 按 bitstream 认（扩展名写 `.ogg` 也认）、WMA 按 ASF GUID 认、DST 与纯 DSD 区分、未知容器返回 `UNKNOWN` 而不是当成不支持、真实文件的魔数探测与 LRU 缓存失效。**不能用 `AudioSystem.getAudioFileTypes()` 做能力探测**——这些 SPI 不通过它暴露，只会返回 `aif/au/wav`，所以只能靠魔数 + 扩展名。
- `playback.PcmOutputPlanTest` / `PcmQuantizeTest` / `SincResamplerTest` / `PcmConverterTest`（8 + 9 + 6 + 11 例）：采样率阶梯（源率 → 48k → 44.1k，>192k 不交给设备）、位深永不超过源、降位必须有 dither（16-bit 静音在 ±1 LSB 内、最坏误差 ≤1.5 LSB 且不 wrap）、sinc 重采样的通带与混叠抑制、`PcmConverter` 的分块读写与 `flush` 幂等。dither 与重采样是"听起来只是有点糊"的那类 bug，没有断言基本查不出来。
- `playback.AudioStreamOpenTest`（3 例）：`AudioSystem.getAudioInputStream(file)` 拿到的是**编码流**（MP3 的位深是 -1、`frameRate` 是 38.28 的压缩帧率），必须经 `openPcmStream()` 换成 PCM 才能进混音管线——直接喂给 `PcmConverter` 会让每一首歌都报"解码错误"。另两例钉住 Vorbis 转换流前两次 `read` 返回 0 不算 EOF，以及永远返回 0 的流必须抛错而不是空转。
- `data.download.Id3v2Test` / `DownloadPathsTest`（11 + 10 例）：ID3v2.3 的帧长是 syncsafe 整数、UTF-16 文本帧带 BOM、APIC 内嵌 JPEG；文件名清洗与重名加 `(2)`。ID3 测试还会用项目已有的 jaudiotagger 反向解码自己写出的标签，确认别的软件也读得懂。
- `data.repo.NeteaseAlbumPagingTest` / `data.model.LyricSubLineTest`（8 + 5 例）：收藏专辑分页的游标推进与翻页去重、日文脚本判定与罗马音行渲染的取舍。

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
      db\                            SQLite 表结构与五个 DAO（与 Android 逐字节相同）
        sqlite\                       JDBC 版 Cursor / SQLiteDatabase / SQLiteOpenHelper / ContentValues
      download\                      下载层：Id3v2（ID3v2.3 写入 / 读回）、DownloadPaths（文件名与去重）、
                                     SongDownloader（并发下载 + ID3 + lrc）、DownloadManager（队列与 StateFlow）、
                                     DesktopDownloadSink（%USERPROFILE%\Music\HexNC 落盘）
      local\                         扫描器（jaudiotagger 读标签）、封面仓库、LRC 解析、DSD 识别
        dsd\                         纯 Kotlin 的 DSF / DFF 容器解析（DsdFormat）与半带 FIR 抽取（DsdDecoder）
      lyrics\                        歌词匹配打分（LyricMatch）与 LRCLIB 客户端
      model\                         Song / Playlist / Lyrics / Ids / 歌词进度
      netease\                       eapi/weapi 加密、登录、接口、JSON 模型、直链 https 化
      prefs\                         设置存储（含桌面新增 volume）
      repo\                          曲库仓库、网易云仓库、在线歌词仓库
    playback\
      AudioEngine.kt                 Java Sound 播放引擎（专用线程、暂停/跳转/增益）
      PcmPipeline.kt                 输出格式候选（24-bit 直通 → 16-bit）、TPDF dither、48-tap sinc 重采样、限幅
      DecodeCapability.kt            格式能力判定（文件头魔数 + 扩展名 + LRU 缓存），供列表徽标与播放前置检查
      DsdAudioFileReader.kt          Java Sound SPI 提供者：AudioSystem 直接拿到 DSD 解出的 PCM
      MediaCache.kt                  直链下载与 1 GB LRU 缓存（播放用临时副本，不是下载目录）
      PlaybackController.kt          队列/顺序/随机/错误跳过/快照，与 Android 版同样的公开 API
      PlaybackMessages.kt            用户可见的播放状态码（不支持格式 / 解码失败 / 设备失败 / 文件不可读各有独立码）
    ui\                              与 Android 版同源的界面 + 桌面文件对话框
      components\DownloadSheet.kt    下载进度面板（完成 / 跳过 / 失败、取消、只重试失败项）
      platform\                      桌面专属：右键点击（PointerInput.kt）与 Swing/AWT 文件对话框
      theme\Tokens.kt                Spacing / Radii / RowMetrics / TypeScale / Motion，双端逐字节相同
    format-matrix.md                 各格式在桌面端与 Android 端的实测支持矩阵
src\main\resources\
  META-INF\services\                  Java Sound SPI 注册（javax.sound.sampled.spi.AudioFileReader）
src\test\kotlin\                       与 Android 版同源的单元测试
packaging\windows\patch-msi.ps1        MSI 数据库后处理（每次构建的新 ProductCode、同版本可覆盖、卸载快捷方式）
```

### 已知限制

- **构建路径**：`createDistributable` 的应用目录跑起来即用，MSI 由 `packageMsi` 自动配好 WiX 生成；唯一的前提是本机能访问 Maven Central 与 GitHub（首次构建时要下 WiX）。
- **解码格式**：Java Sound 能解 MP3（mp3spi）、FLAC（jflac）、OGG（vorbisspi）与 WAV/AIFF/AU（JDK）；这一轮又接了 `javasound-core` / `-aac` / `-alac` / `-ape` 四个 SPI，所以 **m4a（AAC 与 ALAC）、裸 AAC（ADTS）、APE** 也能解了（`format-matrix.md` 有逐格式的实测记录）。桌面端仍然**没有 Opus 与 WMA 解码器**（纯 Java 实现不存在可用来源），DST 压缩的 DSD 也解不了——这三类不再安静地失败，而是入库时就判定、列表带「不支持」徽标、点播时明确说明。**DSD（`.dsf` / `.dff`）** 由内置纯 Kotlin 解码器解析，DSD64 / DSD128 / DSD256 经半带 FIR 抽取成 176.4 kHz PCM 后交给 Java Sound；**192 kHz 及以上**的 PCM 文件一律不把原始采样率交给设备，直接转换到设备支持的采样率再播放。
- **格式矩阵的证据强度**：新接的四个 SPI 里 AAC / ALAC 是**真的用样本解出来的**（`AudioSystem.getAudioInputStream` 返回 AAC 44100 / ALAC 44100 16-bit），FLAC 24-bit / 96 kHz 也真的拿到 24-bit；**APE 只有"provider 已注册"这一层证据**——本机 ffmpeg 没有 APE 编码器，造不出样本，所以矩阵里标的是「推断」。Opus / WMA 的「不支持」同样是实测的（`UnsupportedAudioFileException`）。
- **下载**：只对网易云来源、且有 `neteaseId` 的歌曲可用；下载质量取设置项，服务端对无版权或未开通的音质会返回空直链，此时给的是「无版权或该音质不可用」而不是一个 0 字节文件。**MP3 与 FLAC 都写标签，M4A / ALAC 仍不写**：MP3 是 ID3v2.3，FLAC 是原生 Vorbis comment + PICTURE（音频帧逐字节不变，原文件有足够 padding 时连文件大小都不变）；M4A 的 `ilst` atom 还没做，文件仍然有正确的扩展名与同名 `.lrc`。音质上，请求 `hires` 只是"请求"——服务端按账号与版权回落，播放页与下载面板都会如实标注实际档位。文件名里的非法字符会被替换成 `_`，因此极端情况下两首不同歌可能落到同一个文件名，此时按 `(2)` 递增避让而不是覆盖。桌面端**没有做断点续传**：中断的那次只留 `.part` 并被清理，重下从头开始。
- **在线歌词**：补全结果取决于网易云与 LRCLIB 是否收录了那首曲目，且必须能联网；网易云搜索本身有风控与限速，因此同一首歌 10 分钟内只会请求一次，失败可以重试。请求只包含「歌名 + 歌手 + 专辑」文本，不上传文件内容、路径或任何账号信息（LRCLIB 无需登录）。
- **网易云收藏专辑（2026-11 已修复）**：`/api/album/sublist` 的专辑数组其实在响应顶层的 **`data`** 键下，而模型把字段声明成 `albums`，于是 kotlinx.serialization 永远解出一个空列表——同步把空结果写进 `nc_albums`，界面就一直是「一张都没有」。现在 `NcAlbumSublistResponse` 用 `@SerialName("data")`，并用真实登录账号实测：`albums=46`、`first=Deja Entendu`、`limit=20 → more=true`、`refreshFavouriteAlbums → count=46`；`NeteaseJsonTest` 里有一个不联网的解码用例钉住这个形状。同一批的「最近播放」货架仍然只有排序逻辑有测试覆盖：记录点写在 `startAt()`（已经尝试打开播放的曲目就记一笔，包括随后解码失败的）。
- **降饱和调色**：只有数值被验证（`AuroraPaletteTest` 的四条断言 + 逐主题对比度计算），「看起来是否更舒服」只能人工看；本机无显示设备，因此没有任何截图证据。
- **系统集成**：没有任务栏媒体控制、全局媒体键、SMTC 与托盘图标；这些是 Android 版也没有的，如需可后续加。
- **淡入淡出**：设置项为与 Android 版保持一致的保留项，桌面引擎尚未实现交叉淡入淡出。
- **界面测试**：自适应布局、左侧导航栏、右键菜单与 `⋮` 按钮只由编译和手动运行确认，没有 UI 自动化测试（Compose UI 测试尚未接入 `:test`）。
- **安装包**：MSI 的安装、覆盖安装（1.1.0 over 1.0.0、1.2.0 over 1.1.0）、降级安装（1.0.0 over 1.1.0，实测会真的装回旧版）、同文件重复运行与卸载都已在本机实测（见「Windows 安装、覆盖安装与卸载」）；但 `packageExe` 没有打补丁，重建的同版本 MSI 覆盖安装也未实测。

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
| Playlist sync | Pulls “liked songs” plus your created and subscribed playlists; the playlists page carries its own **“NetEase albums”** entry that opens a full page of the albums favourited on the account (`/api/album/sublist`, falling back to `eapi`), and an album is imported on demand only when tapped — favouriting it never writes to the local library. That page has four explicit states (signed out / loading / failed with retry / empty), so a network failure is visible instead of silently blank. **November 2026 fixed the parsing bug that kept the album page permanently empty**: the album array actually sits under the top-level `data` key while the model declared `albums`, so every decode produced an empty list and the sync cached it; with `@SerialName("data")` a real signed-in account now reports 46 albums, and `limit=20` returns `more=true` |
| Library | The home tab is an index rather than a stack of shelves: under the stats card sit browsing entries (all songs / albums / favourites / recently added / recently played), each opening its own second-level page, and the artists shelf is gone. The home tab keeps only **“Recently played”** (the last 24 ids in play order, kept in preferences rather than a new table) and **“Recently added”** (songs only, 4 rows, newest first), both with a “see all” action |
| Local import | Scans the `Music` folder, or a folder/file you pick (audio plus a sidecar `.lrc`); Settings can also scan the library for lyric files on its own. Every lyric read goes through `decodeLyricBytes()`: BOM first, then a NUL-pattern sniff for BOM-less UTF-16, then strict UTF-8, then GB18030, then lenient UTF-8 — Chinese `.lrc` files are usually GBK, and reading them as UTF-8 used to throw or produce mojibake, which is exactly what made “local lyrics will not import” |
| Lyrics | LRC / word-level `yrc` / translation / romanisation, with adjustable size, line spacing and toggles; when nothing local exists the app searches NetEase and LRCLIB and only auto-imports a strong match (score ≥ 0.86 with a title score ≥ 0.90) — otherwise the player offers a “Search lyrics” picker. “Import local lyrics” in the song / album / artist / search action sheets all use the same picker, which also clears the “these lyrics were deleted” tombstone first — without that the import failed silently |
| Lyric scroll | Centred auto-follow plus a word-by-word karaoke wipe; drag to browse, following resumes 5 s after the last drag, tap a line to seek; a plain un-timed sheet is shown as one list instead of being forced to scroll |
| Playback detail | Artwork, transport and lyrics on one page: two columns in a wide window, stacked sections in a narrow one, so lyrics never need a mode switch. The **seek bar is docked at the very bottom** of the page as a thin rule (3 dp tall, 2 dp rounded ends) with a tabular timestamp row above it; dragging only redraws, and the seek happens on release. The third top-bar button (`i`) opens a **song info** sheet with album, artist, track number, duration, source, **the tier actually granted**, format, file size, lyric availability, added time and file path; the fourth (`⋮`) opens **playback options**, which on the desktop holds the volume slider and an entry point for **immersive mode** |
| Artist detail | Besides local songs, a section lists the albums that artist released on NetEase (`/api/artist/albums/<id>` when the id is known, otherwise an album search filtered by the artist name); tapping one imports the album. The page is useful with zero local songs, so search results open straight into it |
| Custom covers | Per-song and per-playlist cover override from a local image |
| Playlists | Create / rename / add / remove / reorder / delete local playlists; “All local music” is an auto-maintained reserved playlist holding every scanned local song and cannot be renamed or deleted |
| Playback | Sequence / repeat-all / repeat-one / shuffle, with automatic skip-on-error and a reason for the failure (unsupported format, decode failure, device failure, unreadable file, network failure and blocked cleartext each get their own message); DSD (`.dsf`/`.dff`) is decoded to PCM and played (only DST-compressed DSD cannot be decoded and is reported and skipped), and 192 kHz-and-above audio is converted to a rate the device accepts |
| Audio quality | Playback asks NetEase for **Hi-Res** by default, and the tier the service actually **granted** is shown in the song info sheet behind the `i` button (e.g. `FLAC · 1039 kbps`, with “no lossless master” appended when the track has none) — the player page itself no longer carries a pill, and a 320 kbps MP3 is never presented as a master. Output is no longer forced to 16-bit: a 24-bit source goes out as `PCM_SIGNED 24` when the device accepts it, and only a genuine reduction to 16-bit is dithered (**TPDF**, ±1 LSB triangular noise) instead of truncated; a source rate the device also runs at passes through untouched, everything else goes through a **48-tap Hann-windowed polyphase sinc** (256-phase table, cutoff at the lower of the two Nyquists); mono-to-stereo is a same-level copy (never +3 dB or a doubled channel); the last stage clamps, so neither dither nor resampling can clip into a pop |
| Downloads | NetEase songs can be saved as **real files**: “Download” in a song's action sheet, “Download all” on playlist / album / artist pages. The download tier also defaults to **Hi-Res**, i.e. the best the track and the account allow, and the extension follows **what the server actually returned** (a FLAC is written as `.flac`, never renamed to `.mp3`). MP3 gets an ID3v2.3 tag (title / artist / album / track / disc / year / embedded JPEG cover, UTF-16 text frames); FLAC gets native Vorbis-comment and PICTURE blocks (audio frames byte-identical, file size unchanged when the original padding is large enough), and a sidecar `.lrc` is written next to it. A track with no copyright or no such tier reports the reason instead of leaving an empty file; duplicate names in one batch get `(2)`; a file that already exists is skipped; an interrupted transfer only leaves a `.part` that is cleaned up |
| Format support | The scanner accepts 14 extensions, but **whether a file can actually be decoded is checked**: mp3 / flac / wav / ogg / m4a / aac / alac / ape / dsf / dff go through the bundled SPI decoders, while opus, WMA and DST-compressed DSD genuinely have no decoder on the desktop — such songs carry an “Unsupported” badge in lists and explain themselves when tapped instead of throwing a generic decode error mid-track. Detection is “container magic + extension” behind a 512-entry LRU (`DecodeCapability`), so a renamed opus, an opus track hidden inside `.ogg` and an extensionless ASF file are all recognised. The full matrix is in [`format-matrix.md`](format-matrix.md) |
| Search | NetEase combined search (songs / albums / artists / playlists) plus local filtering |
| UI | Miuix 0.9.4 with the aurora gradient theme, dark/light/AMOLED, dynamic colour, cover-seeded colour; there are **nine themes**, the original six plus **Monet / Frosted glass / Mono dark** (the latter two flagged `glassy` and `alwaysDark`); **the palette is desaturated and greyed overall** — every theme's accent / deep / glow / blush passes through `AuroraTheme.soft*` (`soften()`: Rec.601 luma desaturation by 34 %, then a 10 % mix towards `#7C8290`), and the schemes, gradients and artwork placeholders read only those four entries. Lists are **flat rows with a hairline divider** (48 dp artwork, 10 dp corner, 64 dp row height, transparent row fill), closer to Salt Player and the stock NetEase client; sizes, radii, type scale and animation durations live in the new `ui/theme/Tokens.kt` (`Spacing` / `Radii` / `RowMetrics` / `TypeScale` / `Motion`), so one edit moves both platforms together |

### Desktop-specific work

- **A real window** — 1180×780 and resizable; the player and database are released on close.
- **Keyboard shortcuts** — space toggles playback, `Ctrl+←/→` skip tracks, `Ctrl+↑/↓` nudge the volume by 5%, `Ctrl+M` mutes. The handler only runs for events no component consumed, so a space typed in the search field still inserts a space.
- **Adaptive-width layout** — from 840 dp up the main navigation moves from the bottom bar to a left-hand `NavigationRail` (the mini player stays at the bottom), and the playback detail page becomes two columns (artwork and transport left, lyrics right) from 900 dp up, falling back to stacked sections in a narrow window; lists stop at a 1120 dp reading width and centre themselves, and the wide playback detail page is capped and centred at that same width so the two pages agree where the content column is, instead of leaving a title and its row controls a thousand dp apart. The default window is 1180×780, so the wide layout is what you get on launch.
- **Lower saturation** — one `soften()` pass (Rec.601 luma desaturation by 34 %, then a 10 % mix towards `#7C8290`) sits behind all nine themes. Numerically the contrast on a white surface *gains* (the weakest theme, Borealis, goes 2.54 → 3.14); on the dark surface it may dip slightly but stays above 4:1 (the widest dip is Borealis, 7.60 → 6.07). `AuroraPaletteTest` pins the transform, because reading the raw enum colours when building a gradient is the easy mistake — it would leave the gradients and the artwork placeholders glowing while only the colour schemes turned grey.
- **Right-click and a visible action button** — any song or collection row opens the same action sheet on right-click (`PointerMatcher.mouse(PointerButton.Secondary)`) as on long-press, and every row also carries a permanent `⋮` button, because neither right-click nor long-press is discoverable on its own. Long-press still works. The menu is the same `TrackActionsSheet` (favourite, queue, add to playlist, cover, import title, refresh lyrics, delete).
- **Native file dialogs** — folder and file pickers are Swing/AWT dialogs (with the system look and feel installed so a Windows chooser does not render in Metal); no Android runtime-permission dance at all.
- **Its own playback engine** — Java Sound plus SPI decoders (mp3spi / vorbisspi / jflac / JDK built-ins) in place of media3/ExoPlayer, feeding the audio device from one dedicated audio thread.
- **Native DSD decoding** — a pure-Kotlin DSF/DFF parser and half-band-FIR decimator (DSD64/128/256 → 176.4 kHz 16-bit PCM), registered as a Java Sound `AudioFileReader` through `META-INF/services`, so `AudioSystem.getAudioInputStream()` already hands back PCM and no playback code had to change.
- **Very high sample rates** — **anything above 192 kHz stops being offered to the device**: Windows mixers report support for rates they cannot actually render, so the file's own rate is only the first candidate when it is at or below 192 kHz; each candidate is really opened once and a refusing device falls through to 48 kHz / 44.1 kHz (the 176.4 kHz DSD output takes the same path) instead of failing the whole track. Ordinary files keep the original zero-conversion path.
- **On-disk media cache** — NetEase stream URLs expire in ~20 minutes and Java Sound cannot play a URL, so a track is downloaded on first play (keyed `<id>-<quality>`, 1 GB LRU cap). The payoff is instant seeking, no network stutter, and a prefetch of the next track.
- **Volume slider** — the desktop slider now lives in the `⋮` playback-options sheet on the player page, next to a mute toggle, and applies live while playing (the engine is not restarted). Settings keeps its own slider, so the player page is left with artwork, title and transport.
- **Cache management** — Settings shows the cache size and clears it in one click.
- **Online lyric completion** — when no local `.lrc` exists, the song title and artist are sent to NetEase (eapi, up to 15 hits) and LRCLIB (indexed by name and duration, no login or account needed) in parallel, and the local ranker scores each hit on title / artist / duration. Only a hit above the threshold is imported automatically; everything else is listed in the player (title, artist · album, duration, source) for one-tap import. A song is not asked for twice within 10 minutes, and an offline failure can be retried.
- **An immersive player page** — the artwork becomes the background (same image, 72 dp blur, 20 % opacity, pressed under two theme gradients so contrast survives), crossfading over 320 ms on a track change; the cover scales with the window — 380 dp in the wide layout, 62 % of the width capped at 320 dp in the narrow one (it used to be a fixed 268 dp, which read as a thumbnail in a maximised window). Cover radius, title size, artist weight and transport-button size now come from `Radii` / `TypeScale`. There is also a genuinely full-screen **immersive mode** (`⋮` → immersive mode): artwork and lyrics only, artwork on top, scrolling lyrics underneath, top bar and transport hidden, and a tap on the artwork leaves it.
- **Skeletons instead of a spinner** — lists lay down 7 placeholder rows (a 48 dp block plus 62 % / 38 % bars) with a 1200 ms shimmer sweeping across, so a cold start no longer shows a blank pane with an `InfiniteProgressIndicator`.
- **Mini player** — the progress rule went 3 dp → 2 dp (3 dp reads as a divider, not as transport state), the row is 64 dp tall, and the artwork is a 20 %-opacity blurred tint under a 72 % scrim, which keeps the row's text above 4.5:1.
- **Tabular timestamps** — the elapsed / total line uses `fontFeatureSettings = "tnum"`, otherwise the proportional digits shift the whole line once per second.
- **A docked, thin seek bar** — the player's seek control sits at the bottom edge of the content area (`PROGRESS_DOCK_HEIGHT`, 48 dp, is reserved for it), 3 dp tall with 2 dp rounded ends. Dragging only updates the label; `onDragEnd` performs the seek, so a drag does not repeatedly restart decoding and positioning.
- **Downloads on disk** — the single-song menu and the “Download all” action on playlist / album / artist pages write the NetEase stream to a real audio file under `%USERPROFILE%\Music\HexNC` by default (configurable in Settings), named `artist/album/track - title.ext` with illegal characters replaced; at most 2 transfers run at once, duplicate names in a batch get `(2)`, MP3 gets an ID3v2.3 tag (embedded cover included) and a sidecar `.lrc` is written. The progress panel counts completed / skipped / failed, summarises which containers actually landed (e.g. `FLAC ×2 · MP3 ×1`) and how many tracks had no lossless master, can be cancelled and can retry only the failures. Downloads and the media cache are deliberately separate things — the cache is a temporary playback copy (1 GB LRU, sha1 filenames, no extension) and clearing it never touches a downloaded file.
- **A real browser for sign-in** — there is no WebView on the desktop, so sign-in opens the system browser and the session is imported by pasting the cookie — which gets 2FA, captcha and “already signed in on this PC” for free.
- **Its own icon** — the desktop build used to have **no .ico at all**: neither jpackage nor `Window` was given one, so the taskbar, Alt-Tab and the window itself showed the generic Java cup. `packaging\windows\hexnc.ico` (nine sizes, 16 through 256) is now handed to jpackage through `iconFile`, which compiles it into `HexNC.exe`, and the window sets the same artwork from `src\main\resources\hexnc.png` via `Main.appIcon()`. Both come from one image, and that image is the Android launcher mark, so the two platforms finally match. A missing resource only returns `null`; it can never stop the app from starting.

### Build and run

Toolchain: JDK 21, Gradle 9.8.0, Kotlin 2.4.20, Compose Multiplatform 1.12.1, Miuix 0.9.4.

```bat
:: the launcher lives in D:\AI Project\.toolchain\buildwin.cmd and must be called from that directory
cmd /c "buildwin.cmd :run"                 :: run it
cmd /c "buildwin.cmd :test"                :: unit tests
cmd /c "buildwin.cmd :createDistributable" :: runnable application image
cmd /c "buildwin.cmd :packageMsi"          :: MSI installer
```

`createDistributable` writes to `build\compose\binaries\main\app\HexNC\` (**223.5 MB**, self-contained
runtime); run `HexNC.exe` from there and no local Java installation is needed. `packageMsi` writes
`build\compose\binaries\main\msi\HexNC-1.2.0.msi` (**108.0 MB**), a copy of which sits in the project
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
`8E4B71F0…` to `27B65288…`; **then 1.2.0 over that → exit 0, the only entry is `HexNC 1.2.0`
(`{7AE80026-…}`), the jar becomes `HexNC-For-Windows-1.2.0-*.jar`, `HexNC.exe`'s sha256 becomes
`054E8EFA…`, and both Start-menu shortcuts plus the desktop shortcut survive**; downgrading back to
1.0.0 → exit 0, entry back to `HexNC 1.0.0`; running the same 1.2.0 MSI again → exit 0, maintenance
no-op (entry, files and shortcuts unchanged); `msiexec /x {7AE80026-…}` → exit 0 and the Apps entry,
`%LOCALAPPDATA%\HexNC`, both Start-menu shortcuts and the desktop shortcut are gone, with nothing left
in `D:\Config.Msi`.

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

**294 tests in 32 classes, all passing** (`cmd /c "buildwin.cmd :test"` — measured: 0 failures,
0 errors, 0 skipped): the table in the Chinese half lists each class and its case count. The suite is the same source set as the Android build;
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
assertions pin that all four stops of all nine themes come out less saturated, that white-surface
contrast never drops and dark-surface contrast stays above 4:1, and that `gradientStops` is built from
the `soft*` entries.

The FLAC writer is guarded by `data.download.FlacTagsTest` (12 cases): a FLAC is a chain of
length-prefixed blocks, so one wrong length yields a file that looks tagged while players start
decoding in the middle of a metadata block — every case therefore re-parses the output structurally
(STREAMINFO byte-identical and first, audio frames byte-identical, exactly one `0x80` last-flag,
tagging twice byte-identical, file size unchanged when the original padding suffices, blocks inserted
before the audio when there is no padding, blank fields omitted rather than written as `KEY=`, non-FLAC
input rejected, foreign blocks preserved in order), and the last case reads the result back through
jaudiotagger as an independent implementation. `data.repo.GrantedQualityTest` (7 cases) pins that
`lossless`/`hires` count as no downgrade, that `exhigh`/`standard`/a missing level do (silence is not a
promise), that the comparison ignores case, and that container and bitrate pass through untouched.

The lyric-import fix is guarded by `data.local.LyricDecodeTest` (8 cases, again the same file on both
platforms): every read of a `.lrc` sidecar now goes through `decodeLyricBytes()`, and the suite pins the
order it relies on — UTF-8 with and without a BOM, UTF-16 with a BOM and BOM-less UTF-16 sniffed from its
NUL pattern, a real GB18030 lyric (asserted to be non-UTF-8 so the fixture cannot silently become a no-op),
plain ASCII, and the empty input. Without that ordering a GBK file either throws (and the sheet is dropped
as if the user had picked nothing) or is stored as mojibake, which is what "local lyrics will not import"
looked like.

### Known limitations

- **Build path** — the `createDistributable` image runs as-is and `packageMsi` wires WiX up by itself; the only prerequisite is reachable Maven Central and GitHub (WiX is fetched on the first build).
- **Codecs** — Java Sound decodes MP3 (mp3spi), FLAC (jflac), OGG (vorbisspi) and WAV/AIFF/AU (JDK); this round also wired up `javasound-core` / `-aac` / `-alac` / `-ape`, so **m4a (AAC and ALAC), raw ADTS AAC and APE** decode too (`format-matrix.md` records each one). The desktop still has **no Opus or WMA decoder** (no usable pure-Java implementation exists) and cannot decode DST-compressed DSD — those three no longer fail quietly: they are classified on import, badged as “Unsupported” in lists and explained when tapped. **DSD (`.dsf` / `.dff`)** plays through the built-in pure-Kotlin decoder, which decimates DSD64 / DSD128 / DSD256 to 176.4 kHz PCM for Java Sound. **192 kHz-and-above** PCM is never handed to the device at its own rate.
- **How strong the matrix evidence is** — of the four new SPI decoders, AAC and ALAC were **really decoded from samples** (`AudioSystem.getAudioInputStream` reports AAC 44100 / ALAC 44100 16-bit) and FLAC 24-bit / 96 kHz really came back as 24-bit; **APE only has “the provider is registered”** — the ffmpeg on this machine has no APE encoder, so no sample could be made and the matrix marks it as inferred. The Opus / WMA “unsupported” rows are measured (`UnsupportedAudioFileException`).
- **Downloads** — available only for NetEase tracks that carry a `neteaseId`; the tier comes from Settings and a track with no copyright or no such tier yields “no copyright or tier unavailable” rather than a zero-byte file. **MP3 and FLAC both get tags; M4A / ALAC still do not**: MP3 through ID3v2.3, FLAC through native Vorbis-comment and PICTURE blocks (audio frames byte-identical, and the file size does not even change when the original padding is large enough). The M4A `ilst` atom is still untouched, so those files keep the right extension and a sidecar `.lrc` but no tag. On quality: asking for `hires` is only a request — the service falls back by account and copyright, and both the player and the download panel label the tier that was actually granted. Illegal characters in a name become `_`, so two different songs can in principle collide — that is resolved with `(2)` rather than by overwriting. There is **no resume**: an interrupted transfer leaves a `.part` that is cleaned up, and the retry starts from byte zero.
- **Online lyrics** — completion depends on NetEase or LRCLIB actually cataloguing the track and on a working network; NetEase search is rate-limited and risk-controlled, so a given song is only asked about once per 10 minutes and a failure can be retried. Requests contain only title / artist / album text — never file contents, paths or account data (LRCLIB needs no login).
- **Favourited NetEase albums (fixed in November 2026)** — the album array of `/api/album/sublist` actually arrives under the top-level **`data`** key while the model declared `albums`, so kotlinx.serialization always produced an empty list, the sync cached that empty result and the page stayed at “none”. `NcAlbumSublistResponse` now uses `@SerialName("data")`, and a real signed-in account measures `albums=46`, `first=Deja Entendu`, `limit=20 → more=true` and `refreshFavouriteAlbums → count=46`; a decode-only case in `NeteaseJsonTest` pins the shape without touching the network. The “Recently played” shelf from the same batch is still the weaker story: its record point sits in `startAt()` (a track that was opened is recorded even if its decode then failed), that ordering has no test coverage, and only the re-ordering logic in `recentlyPlayed()` (id list order) is pinned.
- **Lower-saturation colours** — only the numbers are verified (`AuroraPaletteTest`'s four assertions plus the per-theme contrast arithmetic); whether the result actually looks better is a human judgement, and there is no display attached to this machine, so there is no screenshot evidence either.
- **Shell integration** — no taskbar transport controls, no global media keys, no SMTC, no tray icon. Android had none of these either; they could be added later.
- **Crossfade** — kept in Settings for parity with Android; the desktop engine does not implement it yet.
- **UI tests** — the adaptive layout, the left navigation rail, the right-click menu and the `⋮` buttons are only confirmed by compilation and manual runs; there are no UI automation tests (Compose UI testing is not wired into `:test`).
- **Installer** — the MSI's install, overwrite-install (1.1.0 over 1.0.0, 1.2.0 over 1.1.0), downgrade install (1.0.0 over 1.1.0, which really does install the older version), identical-file re-run and uninstall paths were all measured on this machine (see “Windows install, overwrite-install and uninstall”); `packageExe` is not patched, and an overwrite-install of a rebuilt same-version MSI was not measured.
