# HexNC 🎧

**一款基于 [Miuix](https://compose-miuix-ui.github.io/miuix/zh_CN/guide/getting-started) 架构的第三方网易云音乐播放器 · 中英双语**
**A third‑party NetEase Cloud Music player built on the Miuix design system · Chinese & English**

---

## 中文

### 这是什么

HexNC 是一个 Android 音乐播放器：它把**网易云歌单同步到本地数据库**，同时支持**导入本机音乐与歌词**，并把两者放在同一个曲库、同一个播放队列里。

- 界面完全使用 **Miuix 0.9.4**（Compose Multiplatform 设计系统），不含任何 Material 组件。
- 语言：**简体中文 / English**，在设置里一键切换，无需重启。
- 主题：**浅色 / 深色 / 跟随系统**，外加 **AMOLED 纯黑** 与 **Material You 取色**。
- 配色：**6 套极光主题**（极光、北境、极夜、霞光、星云、深海）。

### 功能一览

| 功能 | 说明 |
| --- | --- |
| 网易云登录 | **App 内浏览器登录**（直接打开 `music.163.com/m/login`，登录成功自动抓取 `MUSIC_U`）与**粘贴浏览器 Cookie**导入登录态——这两条路径完全不经过登录接口、不被风控拦截；另有扫码登录（内置二维码生成）与手机号 / 邮箱密码登录；登录态持久化保存 |
| 歌单同步 | **登录后自动导入一次**（红心歌单、自建歌单、收藏歌单），歌单页也保留「同步全部」按钮与单歌单下拉同步。列表接口 `/api/user/playlist` 只给歌单元数据、**不含歌曲 ID**（`tracks` 为 `null`，也没有 `trackIds` 字段），所以每个歌单的歌曲 ID 都单独向 `/api/v6/playlist/detail` 取（实测该接口在任意 `n` 下都返回完整 `trackIds`，只有内联 `tracks` 被限制在 10 条），再批量拉取歌曲详情写入本地；**抓取失败或 ID 数少于 `trackCount` 的歌单会被跳过而不是清空**，以免一次网络抖动删掉本地已有的歌曲。歌单页在「网易云歌单」之后有一条**「网易云专辑」入口**，点进去是独立的分页（`Route.NeteaseAlbums`）：账号里收藏的专辑走 `/api/album/sublist` 只读元数据（`api` 失败时自动回退到 `eapi`），点按即把整张专辑按需导入本地再跳转专辑详情——收藏专辑不会在同步时预写曲库，只在你真的打开它时才落库；该页把「未登录 / 加载中 / 读取失败可重试 / 确实为空」四种状态分开显示，接口失败不会再表现成"一片空白"。**2026-11 修掉了让收藏专辑永远是空的解析错误**：专辑数组实际在响应顶层的 `data` 键下，模型却声明成 `albums`，于是永远解出空列表并把空结果写进缓存；改用 `@SerialName("data")` 后，桌面端用真实登录账号实测拿到 46 张（`limit=20` 时 `more=true`），Android 与桌面共用同一份 `NeteaseModels.kt` 与解码用例 |
| 曲库 | 首页是**浏览索引**而不是一堆并列货架：顶部四格统计（歌曲 / 专辑 / 收藏 / 歌单）可点，下面五条入口「全部歌曲 / 专辑 / 我喜欢的音乐 / 最近添加 / 最近播放」各自进入独立分页（`Route.LibrarySection`，分页内可播放全部、长按整条操作）；货架只保留**最近播放**与**最近添加**两段（各 4 条，标题右侧「查看全部」）。**艺术家子集已按需求移除**；收藏、最近添加、**最近播放**（按播放顺序保存最近 24 首的 ID，只存在偏好设置里，不新建数据库表——建表要动 `VERSION`，而 `onUpgrade` 是清库重建的） |
| 本地导入 | **系统媒体库扫描**（一键导入手机里已有的音乐）与**文件夹选择器**批量扫描 mp3/flac/m4a/aac/ogg/opus/wav/wma/ape/alac/mp4/3gp/dsf/dff；同名 `.lrc` 自动配对——优先找音频文件同目录的旁挂文件，再查 MediaStore 里的 `.lrc` 索引，最后遍历用户授权过的文件夹树；选择文件夹时会**持久化 SAF 授权**，重启后文件夹里的歌仍可读取 |
| 全部本地音乐 | 自动维护一个**「全部本地音乐」歌单**：每次导入本地歌曲后，把所有本地音乐并入其中（曲库页的**「最近添加」只列歌曲、只给 4 条**，并有独立的**「最近播放」**货架，一首歌想被找到就不能只靠它）；该歌单不可重命名 / 删除，名称随语言切换 |
| 歌词 | 导入单曲 `.lrc`、网易云 lrc / tlyric（翻译）/ romalrc（音译）/ yrc（逐字）；**本地歌曲在播放详情页显示歌词时会自动寻找旁挂 `.lrc`**（读文件统一走 `decodeLyricBytes()`：先严格 UTF-8、再嗅探 BOM 与无 BOM 的 UTF-16、最后 GB18030 兜底，所以 GBK 编码的中文歌词不会再变成乱码或被当成"没选文件"丢掉）（无需手动操作），设置页另有「扫描本地歌词」用于给旧曲库批量补全；**本地找不到歌词时会再向网易云与 LRCLIB 联网搜索**，只有标题、艺术家、时长三项综合得分 ≥ 0.86 且标题相似度 ≥ 0.90 才自动导入，达不到就只在歌词页给出「搜索歌词」入口，由用户从候选列表里自己挑（宁可留空，也不能贴错歌词）；无时间戳的纯文本歌词按**静态行**显示，而不是被当成「无效歌词文件」丢弃；解析换行、多时间戳、`[mm:ss:xx]` 变体与 offset |
| 歌词排版 | 字号（12–48sp）、行距、翻译字号比例、翻译与音译开关、实时预览 |
| 歌词滚动 | 当前行自动**居中**跟随进度；有逐字歌词（yrc）时按字长比例做卡拉 OK 渐变扫过，否则逐行推进（单行最多按 8 秒铺满）；手动拖动歌词后 5 秒自动恢复跟随，也可点「跟随歌词」立即恢复；点击任意歌词行可跳转到该句；**无时间戳的纯文本歌词**只静态展示，不参与跟随、不显示高亮行，也不响应点击跳转 |
| 自定义封面 | 单曲封面、整张专辑封面、歌单封面，均可本地图片替换或一键恢复默认 |
| Playlist | 新建 / 重命名 / 删除歌单、加入 / 移出歌曲、拖动排序、播放全部 / 随机播放 |
| 播放 | Media3 ExoPlayer + MediaSessionService（通知栏、蓝牙按键、锁屏可控），顺序 / 列表循环 / 单曲循环 / 随机；网易云直链是**明文 HTTP**，会被 Android 9+ 拦截，因此播放地址统一升级为 HTTPS，并按「明文被拦 / 网络不通 / 文件失效 / 格式不支持 / 无法解码」分别提示失败原因；**192 kHz 及以上采样率**会在进入 `AudioTrack` 之前用 `SonicAudioProcessor` 重采样到设备原生采样率（否则 `AudioTrack` 初始化失败，用户看到的只是"无法解码"）；**DSD（`.dsf`/`.dff`）** 由内置纯 Kotlin 解码器先转成 88.2 kHz 16-bit WAV 放进应用缓存再交给 ExoPlayer 播放（DST 压缩的 DSD 解码不了，会明确提示并跳过），而不是给一个含糊的读取错误 |
| 音质与错误码 | 在线播放默认向网易云请求 **Hi-Res**（`netease_quality` 默认 `hires`，接口按账号与版权自动回落到实际可达档位），播放页在艺术家下方显示**实际授予的档位**（如 `FLAC · 1039 kbps`），拿不到无损时明确标注「无无损版权」，而不是让用户误以为听到的是无损。本地文件的「能不能解」现在会被前置判定（`DecodeCapability`：文件头魔数 + 扩展名 + 512 条 LRU 缓存）：**Opus 在 Android 上可解**（media3），WMA / APE / DST 压缩的 DSD 不可解，这类歌在列表里带**「不支持」徽标**，点播时给出明确说明，而不是播到一半才抛一个笼统的解码错误。播放错误码也拆细了：`UNSUPPORTED_FORMAT` / `DECODE_ERROR` / `DEVICE_ERROR` / `FILE_ACCESS` 与 media3 的 `PARSING_CONTAINER_UNSUPPORTED`、`DECODER_INIT_FAILED`、`AUDIO_TRACK_INIT_FAILED`、`IO_NO_PERMISSION` 等一一对应，四类提示各有各的文案与建议 |
| 下载 | 网易云歌曲可保存为**真实文件**，下载音质默认 **Hi-Res**：单曲菜单「下载」、歌单 / 专辑 / 艺术家页「全部下载」。扩展名按**服务端实际返回的容器**决定（返回 FLAC 就存 `.flac`，绝不改名冒充 mp3），MP3 写 ID3v2.3 标签、**FLAC 写原生 Vorbis comment + PICTURE**（标题 / 艺术家 / 专辑 / 音轨 / 碟号 / 年份 / 内嵌封面；M4A / ALAC 仍不写），同名 `.lrc` 一并写出；Android 10+ 走 MediaStore（`Music/HexNC`，写入时 `IS_PENDING` 占位，失败回滚），旧版本回退到应用外部目录，因此**不需要存储权限**。进度面板除完成 / 跳过 / 失败外，还汇总实际容器（`FLAC ×2 · MP3 ×1`）与「其中 N 首没有无损版权」，可取消、可只重试失败项；已存在的文件跳过，同批重名加 `(2)`，中断只留 `.part` 并清理 |
| 搜索 | 歌曲、专辑、艺术家、歌单四类搜索；点按即可把网易云内容导入本地并播放。**艺术家详情页除了本地已导入的歌曲，还会另列该艺术家在网易云发布的专辑**（按 id 走 `/api/artist/albums`，没有 id 时退回专辑搜索并按名字过滤），点按即导入整张专辑 |
| 界面 | 迷你播放条、**播放详情页**（右上角四个动作：歌词、队列、**歌曲信息**、**播放选项**；歌曲信息面板列出该曲的专辑、艺术家、音轨、时长、来源、**服务端实际授予的音质**、真实容器格式、文件大小、是否有歌词、添加时间与文件路径，播放页本身不再挂音质胶囊；播放选项面板里有**沉浸模式**入口与静音切换，Android 不显示音量滑杆，走硬件音量键）、播放队列面板；歌词就长在播放详情页里——宽窗口（≥900 dp）左封面与控制、右歌词分栏，窄窗口上下分区（0.56 / 0.44），不再需要切换显示；顶部标签在宽窗口（≥840 dp）换成左侧 `NavigationRail`，列表在超宽窗口下限制到 1120 dp 阅读宽度并居中，播放详情页的宽布局也按同一宽度居中，两处内容列因此对齐；歌曲行与歌单行右侧有常驻 `⋮`（等价于长按）操作入口，行本身改成**扁平行 + 一条内缩的发丝分隔线**（更像原版网易云 / 椒盐音乐，而不是一张张圆角卡片），行高 64 dp、封面 48 dp、圆角 10 dp，尺寸 / 圆角 / 字阶 / 动效时长集中在 `ui/theme/Tokens.kt`（`Spacing` / `Radii` / `RowMetrics` / `TypeScale` / `Motion`），与 Windows 版逐字节相同；**播放详情页改成沉浸式**——同一张封面模糊 72 dp 作背景、压两层主题渐变保证对比度，换歌 320 ms `Crossfade`；封面随窗口缩放（宽布局 380 dp，窄布局取 62% 宽度且封顶 320 dp，原来是固定 268 dp）；**进度条沉到内容区最底部**（3 dp 高、两端 2 dp 圆角，上方一行等宽时间，拖动只更新显示、松手才 seek）；另有全屏**沉浸模式**（`⋮` → 沉浸模式）：只留封面与歌词，封面在上、滚动歌词在下，顶栏与传输控制隐去，轻点封面退出；列表加载改成 **7 行骨架屏 + 1200 ms 微光**而不是转圈；迷你播放条进度线 3 dp → 2 dp、封面作 20% 透明度模糊底、时间用等宽数字（`tnum`）避免每秒横移；**整体配色降饱和、加灰度**——九套主题（新增「莫奈」「毛玻璃」「暗色纯黑白」，后两者分别带 `glassy` 与 `alwaysDark`，纯黑白主题无视浅色模式）的 accent / deep / glow / blush 全部经过 `AuroraTheme.soft*`（`soften()`：按 Rec.601 亮度去饱和 34%，再与 `#7C8290` 混 10%），方案、渐变、封面占位图都只读这四个入口 |

### 自设计图标

`ic_launcher_foreground.xml` 是手绘矢量图，2026-11 重新设计成**实心六边形瓷砖**：深色背景上铺一块尖顶六边形，
填充极光渐变 `#56BFD4 → #7A73D6 → #D98CAF`，内部播放三角用 `fillType="evenOdd"` **挖空**（露出启动器背景而不是
再画一块深色），边缘再压一道 1.3 dp 半透明白色内描边，让图标在深色壁纸上不糊成一团。选实心剪影是因为它在
16–24 px 的通知栏与桌面上比描边版更稳。单色版（`ic_launcher_monochrome.xml`）沿用同一路径与同样的挖空，
适配 Android 13+ 主题化图标；背景色 `@color/ic_launcher_background` 取瓷砖外的底板色 `#0E111B`。
Windows 端使用同一张图（`packaging/windows/hexnc.ico` + `src/main/resources/hexnc.png`），双端图标因此完全一致。

### 安装

```
HexNC-1.2.0-release.apk     ← 推荐：使用自带签名密钥签名，可直接安装
HexNC-1.2.0-debug.apk       ← 调试版（包名带 .debug 后缀，可与正式版共存）
```

`adb install -r HexNC-1.2.0-release.apk`，或直接把 APK 传到手机上点击安装。

### 构建

项目锁定了一套**经过实际编译验证**的工具链组合：

| 组件 | 版本 |
| --- | --- |
| JDK | Temurin 21 |
| Gradle | 9.8.0 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin | 2.4.20 |
| Compose Multiplatform | 1.12.1 |
| Miuix | 0.9.4 |
| compileSdk / targetSdk | 37 |
| minSdk | 26 |

```powershell
# 仓库自带一键脚本（Windows）
D:\AI Project\.toolchain\build.cmd :app:assembleDebug
D:\AI Project\.toolchain\build.cmd :app:assembleRelease
```

或使用任意 Gradle 9.8 + JDK 21：

```bash
gradle :app:assembleRelease
```

签名配置读取仓库根目录的 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）。
删除该文件即退化为未签名构建。

### 测试

由于开发环境没有 Android 运行时，**单元测试是除编译器之外唯一的自动化验证手段**，全部跑在 JVM 上（JUnit 4，无 Robolectric）：

```powershell
D:\AI Project\.toolchain\build.cmd :app:testDebugUnitTest
```

共 **248 个测试 / 25 个测试类，全部通过**：

| 测试类 | 数量 | 覆盖内容 |
| --- | --- | --- |
| `data.netease.NeteaseCryptoTest` | 12 | MD5 / AES-CBC / AES-ECB 向量、weapi 双重加密、`encSecKey`、eapi 端到端签名 |
| `data.local.LrcParserTest` | 19 | lrc / tlyric / romalrc / yrc 解析、多时间戳、`[offset:]`、合并、导出往返、阿拉伯文区域设置下的 ASCII 输出；**无时间戳的纯文本**（`parsePlainText`）保留每一行、元数据行被跳过，而定时接口对同一文本仍然返回空文档 |
| `data.lyrics.LyricMatchTest` | 24 | 在线歌词匹配：标题 / 艺术家 / 时长三项加权（0.55 / 0.30 / 0.15）、`(Live)`/`[Remastered 2011]`/`【无损】` 等发行版本噪声被剥离而标题里本来就有的括号不误删、全角与标点归一化、`feat.`/`&`/`、` 分隔的多艺术家、时长分档（3 / 8 / 20 秒）、编辑距离与包含关系；**自动导入的判定**：同名不同艺术家、致敬乐队、纯音乐、标题不符一律只作为候选；**致敬乐队必须判为不同艺术家**——一旦给 token 重叠打部分分，"Linkin Park Tribute" 就会越过自动导入线 |
| `data.lyrics.LrclibJsonTest` | 8 | LRCLIB 搜索响应解析：秒 → 毫秒换算（`185.4` → `185 400`）、只有纯文本的条目 `hasSynced = false`、无标题时回退到搜索词、纯音乐条目不携带文本、404 的 `{"error":"Not Found"}` 不是数组、未知字段容错 |
| `data.netease.NeteaseJsonTest` | 11 | 新旧字段名回退（`ar`/`al`/`dt` 与 `artists`/`album`/`duration`）、未知字段容错、搜索响应、无版权歌曲、二维码登录状态码 |
| `data.netease.NeteaseLoginGateTest` | 12 | 风控状态码 `-462` → `VERIFY_REQUIRED` 映射、未知状态码必须终止轮询、验证挑战页面解析、Cookie 导入的各种粘贴形态 |
| `data.model.IdsTest` | 12 | SHA-1 向量、本地文件 ID 归一化（大小写 / 路径分隔符 / 查询串）、`ne:` 命名空间不冲突；**保留歌单 ID** 与随机生成的本地歌单 ID 永不相等、只有它被识别为「全部本地音乐」 |
| `data.model.LyricsTest` | 10 | `indexAt` 二分查找、`offsetMs` 正负偏移、歌词跳转与高亮的一致性；**全无时间戳的歌词**才被判定为不可滚动（`isUnsynced`） |
| `data.netease.RequestIdTest` | 7 | eapi `requestId` 必须是 `<毫秒>_<4 位数字>`：下划线分隔符、位数固定、随机数生成顺序、越界收敛，以及阿拉伯文区域设置下仍为 ASCII |
| `data.netease.NeteaseEndpointTest` | 3 | 锁定 API 主机：eapi 必须走 `interface.music.163.com`，任何回退到 `interfacepc.music.163.com` 的改动都会立刻变红 |
| `data.netease.StreamUrlsTest` | 8 | 播放直链协议升级：只把 `http://` 且属于 `126.net` / `163.com` 及其子域的地址改成 `https://`，其他主机与已加密地址原样返回，空值与畸形 URL 不崩 |
| `data.model.LyricProgressTest` | 11 | 逐字歌词进度（按字长加权、含 offset、越界收敛、空字仍占权重）与逐行进度（下一行时间戳为 null 时直接铺满、8 秒封顶） |
| `playback.PlaybackMessageTest` | 9 | ExoPlayer 错误码 → 用户可见提示的映射：明文被拦、网络失败、文件失效 / 无权限、**格式不支持 / 解码失败 / 设备失败**、其他错误各自独立；12 个常量互不相同，未知错误码不再被当成提示文案直接弹给用户 |
| `data.local.dsd.DsdDecodeTest` | 11 | 自造 DSF / DFF 容器解析：DSF 的 LSB-first + 按块平面交织、DFF 的 MSB-first 字节交织、`fmt ` 里的位深与块大小、`DST ` 压缩与结构损坏的数据必须被拒绝；一阶 sigma-delta 调制的 1 kHz 正弦经半带 FIR 抽取后仍然存在（RMS / 过零检波）、双声道不串音、帧数与头部声明相符（±64 字节）；半带滤波器中心 ±2 的抽头必须为 0、抽取倍数的幂次选择 |
| `playback.AudioResamplingTest` | 7 | 重采样判定：不高于设备采样率时**必须原样直通**（44.1 kHz 在 48 kHz 设备上不重采样）、高于时降到设备采样率、设备采样率未知时回退 48 kHz、异常值收敛到 8000–192000；以及 DSD 扩展名识别（含大小写、SAF 查询串与 `#` 片段，且不会把目录名里的点当成扩展名） |
| `data.netease.NeteasePlaylistIdsTest` | 7 | 歌单歌曲 ID 的来源：列表接口的条目**只有歌曲数、没有 ID**，详情接口才给出完整有序 ID；`trackIds` 优先于内联样例、旧结构回退到内联 `tracks`、非正 ID 被丢弃、空歌单返回空、可用 `trackCount` 检出截断 |
| `ui.theme.AuroraPaletteTest` | 4 | 降饱和 / 加灰度是纯数值变换，因此可以脱离屏幕验证：九套主题的 accent / deep / glow / blush 四个色位**每一支都必须比原色更不饱和**；白底对比度不许下降（实测每支都略有提升，最低的北境主题 2.54 → 3.14），深色底允许小幅下降但必须 ≥ 4:1（最大降幅是北境 7.60 → 6.07）；`gradientStops` 必须由 `soft*` 入口构造而不是读原色——读原色会让渐变与封面占位图仍然是霓虹色、只有配色方案变灰；同一色位重复读取必须稳定 |
| `data.local.LyricDecodeTest` | 8 | 旁挂 `.lrc` 的文本解码入口 `decodeLyricBytes()`：带 / 不带 BOM 的 UTF-8、带 BOM 的 UTF-16、**没有 BOM 的 UTF-16** 靠 NUL 分布嗅探、真实的 GB18030 段落（断言该 fixture 确实不是合法 UTF-8，否则断言会变成空转）、纯 ASCII、空输入；以及同一段「时间戳 + 中文歌词」在 UTF-8 / GB18030 / UTF-16LE 三种编码下解出来必须一模一样。顺序很关键：先严格 UTF-8 再 GB18030，中文 `.lrc` 才不会先是乱码、后是被当成"没选文件"丢掉 |
| `playback.DecodeCapabilityTest` | 11 | 能力判定（Android 版）：**Opus 可解**（media3）、WMA 按扩展名与 ASF GUID 都判阻塞、APE 阻塞、DFF 里的 DST 与纯 DSD 区分、DSF 缺魔数时给 `UNKNOWN`、`content://` 与 null 路径不误判、真实文件的魔数探测与 LRU 缓存失效、缺失文件返回 `UNKNOWN` 而不是"不支持" |
| `data.download.Id3v2Test` | 11 | ID3v2.3 写入：syncsafe 帧长、UTF-16 带 BOM 的文本帧、APIC 内嵌 JPEG、多帧顺序与覆盖、已有标签先剥离再写、读回与 `hasTag` 的严格头部校验；并用项目已有的 jaudiotagger 反向解码自己写出的标签，确认别的软件也读得懂 |
| `data.download.FlacTagsTest` | 12 | FLAC 标签写入：STREAMINFO 逐字节不动且仍在首位、`VORBIS_COMMENT` 用 LE 长度与小端键值（空值整键省略）、`PICTURE` 按 BE 写类型 3 / `image/jpeg`、`0x80` 只打在最后一个元数据块、padding 复用后文件大小不变、非 FLAC 输入直接抛错；同样用 jaudiotagger 独立回读，且断言音频帧字节与输入完全一致 |
| `data.download.DownloadPathsTest` | 10 | 下载路径：非法字符清洗（连续非法字符合并为一个 `_`）、`艺术家/专辑/音轨 - 标题.ext` 的拼装、扩展名小写与空值回退 `mp3`、无扩展名时重名追加 `(2)`、已存在文件的避让 |
| `data.repo.NeteaseAlbumPagingTest` | 8 | 收藏专辑分页：游标推进、翻页去重、`hasMore` 判定、末页不足一页、空账号返回空而不是报错 |
| `data.repo.GrantedQualityTest` | 7 | 「实际授予档位」的判定：`lossless` / `hires` 不算降级，`exhigh` / `standard` / 空字符串算降级，大小写不敏感，容器与码率原样透传——播放页那句「无无损版权」就由这条规则决定，判错会冤枉真的有母带的歌 |
| `data.model.LyricSubLineTest` | 5 | 日文脚本判定与罗马音副行：假名 / 汉字混排判定、纯中文不产生罗马音行、副行缩放比例限制在 0.5–1.0 |

这些测试在开发过程中**真的抓到了三个 bug**：`LrcParser.formatStamp` 与 `NeteaseIdentity.randomHex` 都使用了区域设置相关的 `String.format`，在阿拉伯语 / 波斯语 / 孟加拉语设备上会写出非 ASCII 数字，导致歌词文件不可读、请求签名 Cookie 非法（已改为显式十六进制表 / `Locale.ROOT`）；另外 `parseCookieInput` 会把一个孤立的 `;` 当成裸 token，生成名为 `MUSIC_U` 的空值 Cookie（已改为仅当输入是单个片段时才视作裸 token）。`NeteaseEndpointTest` 与 `RequestIdTest` 属于事后补上的**防回归**测试：eapi 网关地址曾被误写成 `interfacepc.music.163.com`，`requestId` 也曾缺少分隔下划线——这两类错误都不会让编译失败，只会在真机上表现为"说不清的异常"，所以必须用测试钉住。另有第四个 bug 不是测试抓到的，而是用探针脚本复刻 App 的请求定位的：网易云返回的播放直链是明文 `http://`，被 Android 9+ 的明文流量策略拦下——这正是「封面、艺术家、歌词都正常，唯独播放失败」的原因（三者都走 HTTPS）。修完之后补上了 `StreamUrlsTest` 与 `PlaybackMessageTest` 作为护栏。

第五个 bug 同样是探针发现的，而且是用户先报上来的：「登录后歌单是导进来了，但歌单里一首歌都没有」。原因是批量同步当时从 `/api/user/playlist` 的**列表条目**里读歌曲 ID，而那个接口根本不返回 ID（`tracks` 是 `null`，也没有 `trackIds` 字段），于是每个歌单都拿到了正确的歌单名与歌曲数，成员列表却被写成空。详情接口 `/api/v6/playlist/detail` 在**任意 `n`** 下都返回完整 `trackIds`（965 首的歌单在 `n` = 0/1/10/1000/100000 下都是 965），内联 `tracks` 则永远被截断到 10 条——所以旧代码里的 `n=100000` 除了把响应体撑大之外没有任何作用。`NeteasePlaylistIdsTest` 现在把「列表形状」与「详情形状」的区别钉死，同时修好了「登录后并不自动导入」这个独立缺陷（现在每个账号只在登录态首次出现时自动导入一次，失败会撤销标记以便下次重试）。

DSD 支持带来的新测试类是 `data.local.dsd.DsdDecodeTest`（11 例）：容器解析、sigma-delta 正弦的抽取还原、双声道分离、DST / 损坏数据拒绝、半带滤波器与抽取倍数。之所以能测，是因为解码器是纯 Kotlin 的（`data/local/dsd/DsdFormat.kt` 解析、`data/local/dsd/DsdDecoder.kt` 半带 FIR 抽取），与 Windows 版逐字节相同；`playback/DsdTranscoder.kt` 只负责把结果写成 WAV 缓存，它依赖 `Context.cacheDir` 与 `contentResolver`，因此**没有单元测试**，只做过编译验证。

这一轮新增的四组测试钉住的是"悄悄退化也不会被发现"的地方：`playback.DecodeCapabilityTest`（11 例）把「Opus 在 Android 上可解、WMA / APE / DST 不可解、`content://` 与缺失文件不能误判为不支持」写死；`data.download.Id3v2Test`（11 例）与 `data.download.DownloadPathsTest`（10 例）钉住 ID3v2.3 的 syncsafe 帧长 / UTF-16 BOM / APIC 内嵌封面与文件名清洗去重；`data.repo.NeteaseAlbumPagingTest`（8 例）与 `data.model.LyricSubLineTest`（5 例）分别钉住收藏专辑翻页游标和日文脚本判定。ID3 测试不只断言字节形状，还会用项目已有的 jaudiotagger 把自己写出的标签读回来——只验证"写进去的字节等于我期望的字节"会漏掉整类不合规但自洽的错误。

降饱和那一次改动的护栏是 `ui.theme.AuroraPaletteTest`（4 例，两端逐字节相同的同一份文件）：颜色调校本来看不见、也最容易改错（构造渐变时顺手读原色，配色方案变灰而渐变还是霓虹色），而它是纯数值函数，所以把「每一支都更不饱和」「对比度不许变差」「渐变必须读 `soft*` 入口」三类断言钉进测试。

### 架构

```
com.hexnc.music
├─ MainActivity / HexNCApp         单一 Activity + 进程级 AppContainer
├─ di.AppContainer                 手写依赖注入（无 Hilt/KSP）
├─ data
│  ├─ model        Song / Playlist / Lyrics / Ids / LyricProgress
│  ├─ db           SQLiteOpenHelper + 手写 DAO（无 Room）
│  ├─ local        LrcParser、CoverStore、LocalScanner(SAF)、dsd/（DsdFormat 容器解析 + DsdDecoder 半带 FIR 抽取）
│  ├─ netease      eapi 加密、Cookie、API、模型、Identity、StreamUrls（明文→HTTPS）、trackIdList（歌单 ID 提取）
│  ├─ prefs        SharedPreferences 设置
│  └─ repo         LibraryRepository（本地） / NeteaseRepository（云端）
├─ playback        PlaybackService（Media3）+ PlaybackController（MediaController）+ DsdTranscoder（DSD → 88.2 kHz WAV 缓存）
└─ ui
   ├─ HexNCRoot    主题注入、NavDisplay 单回退栈
   ├─ theme        Aurora 调色板 + MiuixTheme 桥接
   ├─ i18n         Strings（约 260 条中英文案）
   ├─ components   CoverArt / SongRow / MiniPlayerBar / TrackActionsSheet …
   └─ screens      10 个页面
```

刻意没有引入 Room、Hilt、DataStore：全部改用可读的手写实现，消除注解处理器的版本风险。

### 已知限制

- 网易云接口为**非官方**接口，随时可能失效；本项目只使用 `api` 与 `eapi`（`weapi` 已失效，返回 200 空 body）。
- **登录受网易云风控影响**：部分网络环境下，服务端会对扫码轮询与密码登录一律返回 `-462`（要求先过验证码），此时扫码页会直接显示验证入口，而不再无休止转圈。实测这种拦截发生在**网络出口 IP 层面**——本机用五种不同的客户端指纹（网页 / 移动 UA、`os=pc` / `os=android` Cookie、`music.163.com` / `interface.music.163.com` / `interfacepc.music.163.com`、有无 Cookie）得到的响应完全一致，连故意填错的密码也被同一个门拦下，因此并不是本项目指纹伪造得不像。若你的设备也遇到同样提示，请改用**两条不经过登录接口的路径**：①**浏览器登录**——在登录页切到「浏览器」标签，App 内直接打开 `music.163.com/m/login`，登录完成后自动抓取 `MUSIC_U` 并导入；②**Cookie 登录**——在任意浏览器登录 `music.163.com`，复制 `MUSIC_U`（以及可选的 `__csrf`）粘贴进 App。**请注意**：反复重试扫码或密码登录可能触发更严格的安全验证，甚至导致账号被临时冻结（社区有 3–7 天的报告，属二手信息）。
- VIP / 版权受限歌曲无法获取播放地址，会被跳过并提示，而不是塞进队列后播放失败。
- 网易云返回的播放直链是**明文 `http://`**；Android 9（API 28）起系统默认禁止明文流量，而本项目 targetSdk 37，所以**能读到封面/艺术家/歌词，却无法播放**。HexNC 只在网易云自家域名（`126.net` / `163.com` 及其子域）上把协议升级为 `https://`（实测同一批 URL 走 TLS 能正常返回 `206` + `audio/mpeg`），并附带一份 `network_security_config`：默认仍**禁止**明文，只给这两个域名留一条兜底白名单，以免升级不了的直链被平台直接拦死。播放失败时会区分「明文被拦 / 连不上服务器 / 文件失效 / 格式不支持 / 无法解码」五种提示；扫描出来的 DSD（`.dsf`/`.dff`）现在由内置的纯 Kotlin 解码器（`DsdFormat` + `DsdDecoder`，与 Windows 版逐字节相同）转成 88.2 kHz 16-bit WAV 存到应用缓存后播放，只有 DST 压缩或结构损坏的 DSD 才会明确提示并跳过。
- **下载只对网易云来源生效**，且必须带 `neteaseId`；音质取设置项，遇到无版权 / 无此音质会给明确原因而不是 0 字节文件。**MP3 与 FLAC 都写标签，M4A / ALAC 仍不写**——MP3 用 ID3v2.3，FLAC 用原生 `VORBIS_COMMENT` + `PICTURE`（`FlacTags`，只改元数据块，音频帧逐字节不动）；M4A 的 `ilst` 是另一套容器手术，按风险收益比仍跳过，文件仍有正确扩展名与同名 `.lrc`。Android 10+ 落盘走 MediaStore（`Music/HexNC`，`IS_PENDING` 占位，失败回滚），**没有做断点续传**：中断只留 `.part` 并清理，重试从头开始。
- **格式矩阵的证据强度**：Android 端的判定表是「按 media3 的 `MimeTypes` + 魔数」，Opus 判为可解、WMA / APE / DST 判为不可解；但本环境**没有 Android 运行时**，因此"media3 在这台手机上是否真的解得开某个 Opus 文件"只在编译与单元测试层面被确认（`DecodeCapabilityTest` 钉的是判定逻辑，不是解码器行为）。桌面端那一半是真的解出来的，见 Windows 仓库的 `format-matrix.md`。
- 网易云 `/api/register/anonimous`（匿名登录）已失效，因此未启用。
- 构建产物 `assembleRelease` **未开启 R8 混淆**，以避免未经真机验证的 keep 规则导致运行期崩溃。
- 本项目**未在真机 / 模拟器上运行过**：当前开发环境没有可用的 Android 运行时。验证方式是完整编译 + 248 个 JVM 单元测试 + 生成已签名 APK。
- 因此有九件事**无法在本环境证明**：一是通过人机验证后扫码轮询是否真的恢复（需要真机 + 真实账号）；二是 App 内浏览器登录页在真机上能否正常渲染并走完登录流程；三是播放直链改为 HTTPS 后在真机上是否能完整播放（探针只能证明同一批 URL 走 TLS 会返回 `206` 与 `audio/mpeg`），以及歌词滚动的实际观感；四是登录后的自动导入是否真的把歌单成员写进本地库（需要真机 + 真实账号，探针只能证明详情接口返回的 ID 列表完整），以及歌单页新增的「网易云专辑」区是否真能读到收藏专辑——**2026-11 已经找到并修掉了那个"永远是空"的原因**（专辑数组在响应顶层的 `data` 键下，模型却声明成 `albums`，`@SerialName("data")` 之后桌面端用真实账号实测 46 张），Android 与桌面共用同一份模型和同一个纯解码用例；但"这个账号在 Android 真机上能否拿到同样一批专辑"仍需真机联网确认；五是 192 kHz 以上歌曲经重采样后能否真的播放——判定逻辑有单元测试，但 `AudioTrack` 在具体机型上真正接受哪些采样率只能真机验证；六是**本地歌词的实际命中率**：Android 13+ 起媒体库对非媒体文件（`.lrc`）不再对第三方应用可见，此时只能依赖用户授权过的文件夹树，能否找到取决于用户是否用「导入文件夹」导入过（因此设置页保留了「扫描本地歌词」用于补扫）；七是**在线歌词的实际命中率与歌词质量**——匹配规则、阈值与响应解析都覆盖了单元测试，但"网易云 / LRCLIB 能否搜到这首歌、搜到的是不是同一版录音"只能联网真机验证，且网易云搜索本身还要过风控与频控；八是 **DSD 的实际播放**：容器解析与半带抽取有 11 个单元测试，但 `DsdTranscoder` 依赖 `Context.cacheDir` 与 `contentResolver`（本仓库没有 Robolectric / instrumented 测试），因此缓存写入、`.part` 改名、损坏文件的清理与 512 MB 淘汰路径，以及转码后的 WAV 能否被 ExoPlayer 正常播放，都只能在真机上验证；九是**新版界面的实际观感与可用性**——播放详情页的分栏 / 分区、宽窗口的左侧导航栏、行尾 `⋮` 按钮、1120 dp 阅读宽度（播放详情页的宽布局现在也按同一宽度居中）、以及**降饱和后的实际观感**都只经过编译验证与颜色数值断言（仓库没有接入 Compose UI / instrumented 测试），窄屏下播放器与歌词各占 0.56 / 0.44 是否足够、短屏手机是否需要调整，只能在真机或模拟器上确认。

---

## English

### What it is

HexNC is an Android music player that **syncs NetEase Cloud Music playlists into a local database**
*and* **imports music and lyrics already on the device**, exposing both through one library and one
playback queue.

- The UI is **100% Miuix 0.9.4** (a Compose Multiplatform design system) — no Material components.
- **Chinese / English**, switchable at runtime without restarting.
- **Light / Dark / System**, plus **AMOLED black** and **Material You** dynamic colour.
- **Six aurora palettes**: Aurora, Borealis, Polar, Flamingo, Nebula, Abyss.

### Features

| Area | Detail |
| --- | --- |
| Sign‑in | **In‑app browser sign‑in** (opens `music.163.com/m/login` and harvests `MUSIC_U` on success) and **pasted browser cookie** — neither path touches the login endpoints, so risk control never sees it; plus QR code (generated in‑app) and phone / email + password; session persisted |
| Playlist sync | **Imports once automatically after sign‑in** (the liked playlist, your own playlists and subscribed ones); the one‑tap “sync all” button and per‑playlist refresh remain. The `/api/user/playlist` listing returns metadata only and **no track ids at all** (`tracks` is `null` and `trackIds` is absent), so each playlist's ids are fetched separately from `/api/v6/playlist/detail` (probed: a complete `trackIds` list at any `n`, while the inline `tracks` array is capped at 10), and the songs are then hydrated through `songDetail`; **a playlist whose fetch fails, or whose id count is below its `trackCount`, is skipped rather than emptied**, so one network hiccup cannot delete songs already stored locally. The playlists tab also gained a **“NetEase albums”** section after the playlists: the account's favourited albums come from `/api/album/sublist` as metadata only (the plain `api` call falls back to `eapi`), and tapping one imports that album on demand before opening it — favouriting an album never writes anything to the local library by itself |
| Library | The home tab is a **browse index**, not a wall of shelves: four tappable stat cells (songs / albums / favourites / playlists) and five entries — all songs, albums, favourites, recently added, recently played — each opening its own page (`Route.LibrarySection`) with play-all and per-row actions, while only **recently played** and **recently added** stay on the home tab as four-row shelves with a “see all” link. **The artist shelf was removed on request.** Recent history is the last 24 ids in play order, kept in preferences rather than a new table — a table would need a `VERSION` bump and `onUpgrade` rebuilds the library |
| Local import | **MediaStore scan** (one tap, imports the music already on the phone) and a **folder picker** via SAF; scans mp3/flac/m4a/aac/ogg/opus/wav/wma/ape/alac/mp4/3gp/dsf/dff and pairs sidecar `.lrc` files — the audio file's own directory first, then a MediaStore index of `.lrc` names, then every folder tree the user has granted; the SAF grant is **persisted**, so those folders stay readable after a restart |
| All local music | An auto‑maintained **“All local music” playlist**: every local import merges all local songs into it (the library tab's **“recently added” shelf lists songs only and stops at 4 rows**, and a separate **“recently played”** shelf sits above it, so a song with no playlist of its own is otherwise unreachable); it cannot be renamed or deleted, and its title follows the app language |
| Lyrics | Single `.lrc` import; NetEase lrc / tlyric / romalrc / yrc; **local songs find a sidecar `.lrc` automatically when the playback detail page shows lyrics** (every sidecar read goes through `decodeLyricBytes()`: strict UTF-8 first, then BOM and BOM-less UTF-16 sniffing, then GB18030 — so a GBK Chinese lyric no longer turns into mojibake or gets dropped as if nothing were picked), and Settings offers “Scan local lyrics” to back‑fill an existing library; **when nothing local matches, it searches NetEase and LRCLIB online** and imports automatically only when title, artist and duration together score ≥ 0.86 with a title similarity ≥ 0.90 — below that the lyric page merely offers a “Search lyrics” picker so the user chooses from the candidates (an empty pane beats the wrong lyrics); an untimed plain‑text file is shown as static lines instead of being discarded as invalid; multi‑timestamp, `[mm:ss:xx]` variant and offset parsing |
| Lyric layout | Font size (12–48 sp), line spacing, translation scale, translation & romanisation toggles, live preview |
| Lyric scroll | The active line auto‑centres; with word‑level lyrics (yrc) a karaoke wipe sweeps each word in proportion to its length, otherwise it advances line by line (capped at 8 s per line); a manual drag pauses following for 5 s and a “Follow lyrics” button restores it instantly; tapping any line seeks to it. An **untimed plain‑text** sheet is shown statically — no following, no highlighted line, no seek on tap |
| Custom covers | Per‑song, per‑album and per‑playlist covers from any local image, with one‑tap reset |
| Playlists | Create, rename, delete, add, remove, reorder, play all, shuffle |
| Playback | Media3 ExoPlayer + `MediaSessionService` (notification, Bluetooth, lock screen); in‑order / repeat all / repeat one / shuffle. NetEase hands out **plain‑HTTP** stream links, which Android 9+ blocks, so they are upgraded to HTTPS and failures are reported as “blocked cleartext / network unreachable / file missing / format unsupported / undecodable”. **192 kHz and above** is resampled with `SonicAudioProcessor` down to the device's native rate before it reaches `AudioTrack` (otherwise `AudioTrack` fails to initialise and the user only ever sees “undecodable”); **DSD (`.dsf`/`.dff`)** is decoded by the built-in pure-Kotlin decoder into an 88.2 kHz 16-bit WAV in the app cache and then handed to ExoPlayer (only DST-compressed DSD cannot be decoded and is reported and skipped) |
| Audio quality & errors | Playback asks NetEase for **Hi-Res by default** (`netease_quality` defaults to `hires`, and the API falls back to whatever the account and the copyright actually allow), and the detail page shows the **tier that was granted** right under the artist (e.g. `FLAC · 1039 kbps`), tagging tracks that came back lossy with `no lossless master` instead of letting you assume otherwise. Whether a local file can be decoded is decided **before playback** (`DecodeCapability`: container magic + extension + a 512-entry LRU cache): **Opus is decodable on Android** (media3) while WMA, APE and DST-compressed DSD are not, so those rows carry an **“Unsupported” badge** and refuse with an explanation instead of failing halfway through with a generic decode error. Error codes are split as well — `UNSUPPORTED_FORMAT` / `DECODE_ERROR` / `DEVICE_ERROR` / `FILE_ACCESS` map one-to-one onto media3's `PARSING_CONTAINER_UNSUPPORTED`, `DECODER_INIT_FAILED`, `AUDIO_TRACK_INIT_FAILED`, `IO_NO_PERMISSION` and friends, each with its own wording and advice |
| Downloads | Downloads default to **Hi-Res** too. Any NetEase-sourced song can be saved to the device from its row menu (or a whole album / playlist via “Download all”): a real file in the format the API actually returns, an ID3v2.3 tag for MP3 and a native `VORBIS_COMMENT` + `PICTURE` block for FLAC (M4A / ALAC still get none), carrying title / artist / album / track number / year and an embedded cover, a sidecar `.lrc` when a lyric exists, and a progress sheet with complete / skipped / failed counts plus a container tally (`FLAC ×2 · MP3 ×1`) and how many tracks came back lossy, cancel and retry. Two at a time, never blocking playback; a file that is already there is reported as skipped rather than overwritten |
| Format support | MP3, FLAC (16 and 24 bit), M4A (AAC / ALAC), ADTS AAC, Ogg Vorbis, Opus, WAV, DSD (`.dsf` / `.dff`, DST excluded) play; WMA, APE and DST-compressed DSD are detected and reported as unsupported |
| Search | Songs, albums, artists and playlists; tapping imports the NetEase item locally and plays it. **An artist page also lists that artist's released albums from NetEase** next to the imported songs (`/api/artist/albums` when the id is known, otherwise an album search filtered by name), and tapping one imports the album |
| Chrome | Mini player, the **playback detail page** (four top-right actions: lyrics, queue, **song info**, **playback options**; the info sheet lists album, artist, track number, duration, source, **the tier actually granted by the service**, the real container format, file size, whether lyrics exist, date added and the file path, so the player page itself no longer carries a quality pill, while the options sheet holds an **immersive mode** entry and a mute toggle — Android shows no volume slider because it uses the hardware keys), queue sheet. Lyrics live inside the detail page — artwork and transport on the left with lyrics on the right from 900 dp up, stacked sections (0.56 / 0.44) in a narrow window — so no mode switch is needed. From 840 dp up the top tabs become a left-hand `NavigationRail`, lists stop at a 1120 dp reading width and centre themselves, and the wide playback detail page is capped and centred at that same width so the two content columns line up, every song or collection row carries a permanent `⋮` (the same actions as long-press) and the rows are **flat with a single inset hairline divider** (closer to stock NetEase / Salt Player than to a stack of rounded cards). **The whole palette is desaturated and greyed**: all nine themes (including the new Monet, Frosted glass and Mono dark — the last two carrying `glassy` and `alwaysDark`, so Mono dark ignores light mode) pass every accent / deep / glow / blush through `AuroraTheme.soft*` (`soften()`: Rec.601 luma desaturation by 34 %, then a 10 % mix towards `#7C8290`), and the schemes, gradients and artwork placeholders read only those four entries. The player also draws the **current cover behind everything**, blurred (72 dp) and faded to 62 %, under two vertical scrims, so the page follows the artwork without hurting text contrast; **the seek bar is docked at the bottom edge** of the content area as a thin rule (3 dp tall, 2 dp rounded ends, tabular timestamps above it, dragging only redraws and the seek happens on release), and **immersive mode** (from the `⋮` sheet) leaves nothing but the artwork above scrolling lyrics, with a tap on the artwork to return; lists load into a **7-row skeleton with a 1200 ms shimmer** instead of a spinner, the mini player's progress line is 2 dp over a 20 % cover wash, and every duration is set in **tabular figures** so the clock does not shift the row as seconds tick |

### The icon

`ic_launcher_foreground.xml` is hand‑drawn vector art, redesigned in 2026‑11 as a **solid hexagon
tile**: a pointy‑top hexagon filled with the aurora gradient `#56BFD4 → #7A73D6 → #D98CAF`, a play
triangle knocked out of it with `fillType="evenOdd"` (so the launcher background shows through
instead of a second dark shape being painted on top), and a 1.3 dp semi‑transparent white inner rim
so the edge stays crisp on dark wallpapers. The solid silhouette was chosen because it holds up
better than an outline at 16–24 px in the status bar and on the home screen. The monochrome variant
(`ic_launcher_monochrome.xml`) reuses the same path and the same knockout for Android 13+ themed
icons; `@color/ic_launcher_background` is the plate colour `#0E111B` around the tile. Windows ships
the same artwork (`packaging/windows/hexnc.ico` plus `src/main/resources/hexnc.png`), so the two
platforms carry identical icons.

### Install

```
HexNC-1.2.0-release.apk     ← recommended; signed and directly installable
HexNC-1.2.0-debug.apk       ← debug build (package id ends in .debug, installs side by side)
```

`adb install -r HexNC-1.2.0-release.apk`, or copy the APK to the phone and tap it.

### Build

The project pins a toolchain combination that has actually been compiled end to end:

JDK 21 (Temurin) · Gradle 9.8.0 · AGP 9.4.1 · Kotlin 2.4.20 · Compose Multiplatform 1.12.1 ·
Miuix 0.9.4 · compileSdk/targetSdk 37 · minSdk 26.

```powershell
D:\AI Project\.toolchain\build.cmd :app:assembleDebug
D:\AI Project\.toolchain\build.cmd :app:assembleRelease
```

Signing is read from `keystore.properties` at the repo root. Delete that file to fall back to an
unsigned release build.

### Tests

Because the environment has no Android runtime, **unit tests are the only automated verification
besides the compiler**. They run on the JVM only (JUnit 4, no Robolectric):

```powershell
D:\AI Project\.toolchain\build.cmd :app:testDebugUnitTest
```

**248 tests in 25 classes, all passing:**

| Test class | Tests | Covers |
| --- | --- | --- |
| `data.netease.NeteaseCryptoTest` | 12 | MD5 / AES-CBC / AES-ECB vectors, the weapi double encryption, `encSecKey`, eapi end-to-end signing |
| `data.local.LrcParserTest` | 19 | lrc / tlyric / romalrc / yrc parsing, multi-timestamps, `[offset:]`, merging, export round-trip, ASCII output under an Arabic locale; **untimed plain text** (`parsePlainText`) keeps every line while the timed parser still returns an empty document for the same input |
| `data.lyrics.LyricMatchTest` | 24 | online lyric matching: the weighted title / artist / duration score (0.55 / 0.30 / 0.15), stripping release noise such as `(Live)`, `[Remastered 2011]` and `【无损】` without dropping brackets that are part of the title, full-width and punctuation folding, multi-artist fields split on `feat.` / `&` / `、`, the 3 / 8 / 20-second duration bands, edit distance and containment; and **what may be imported automatically** — a same-named track by another artist, a tribute act, an instrumental or a mismatched title all stay suggestions. **A tribute act must score as a different artist**: as soon as token overlap earned partial credit, “Linkin Park Tribute” cleared the auto-import bar |
| `data.lyrics.LrclibJsonTest` | 8 | decoding the LRCLIB search response: the seconds → milliseconds conversion (`185.4` → `185 400`), a plain-only entry reporting `hasSynced = false`, a blank title falling back to the searched one, an instrumental carrying no text, the 404 `{"error":"Not Found"}` body not being a result array, and unknown members being ignored |
| `data.netease.NeteaseJsonTest` | 11 | modern-vs-legacy field fallback (`ar`/`al`/`dt` vs `artists`/`album`/`duration`), unknown-field tolerance, search responses, unplayable tracks, QR login states |
| `data.netease.NeteaseLoginGateTest` | 12 | the `-462` risk-control code mapping to `VERIFY_REQUIRED`, unknown codes terminating the poll, parsing the verification challenge, and every shape of pasted cookie |
| `data.model.IdsTest` | 12 | SHA-1 vector, local file id normalisation (case / separators / query strings), `ne:` namespace collisions; the **reserved playlist id** can never equal a generated local one, and only it is recognised as “All local music” |
| `data.model.LyricsTest` | 10 | `indexAt` binary search, positive and negative `offsetMs`, agreement between lyric seeking and highlighting; a sheet is “unsynced” only when **every** line lacks a timestamp |
| `data.netease.RequestIdTest` | 7 | the eapi `requestId` shape `<millis>_<4 digits>`: the `_` separator, fixed width, digit-generator ordering, out-of-range clamping, and ASCII output under an Arabic locale |
| `data.netease.NeteaseEndpointTest` | 3 | pins the API hosts: eapi must go to `interface.music.163.com`, so any regression back to `interfacepc.music.163.com` turns red immediately |
| `data.netease.StreamUrlsTest` | 8 | the cleartext→TLS upgrade: only `http://` URLs on `126.net` / `163.com` and their subdomains are rewritten, every other host and every already-encrypted URL comes back untouched, and blank or malformed URLs never throw |
| `data.model.LyricProgressTest` | 11 | word-level karaoke progress (weighted by word length, offset-aware, clamped, blank words still carry weight) and line-level progress (a null next stamp fills immediately, 8-second cap) |
| `playback.PlaybackMessageTest` | 9 | the ExoPlayer error-code → user-facing message mapping: blocked cleartext, network failure, **a missing or unreadable file**, **an unsupported container**, **a decode failure** and **a device / `AudioTrack` failure** all stay distinct, the twelve constants are pairwise different, and an unknown code is no longer shown to the user as if it were a message |
| `data.local.dsd.DsdDecodeTest` | 11 | parsing hand-built DSF / DFF containers (DSF's LSB-first planar blocks, DFF's MSB-first byte interleaving, the `fmt ` bit depth and block size), rejecting `DST `-compressed and structurally broken data, a first-order sigma-delta 1 kHz sine surviving the half-band decimation (RMS / zero-crossing), stereo channels staying separate, the frame count matching the header (±64 bytes), the half-band taps at the centre ±2 being zero, and the decimation factor staying a power of two |
| `playback.AudioResamplingTest` | 7 | the resampling decision: anything **at or below** the device rate must pass through untouched (44.1 kHz on a 48 kHz device is never resampled), anything above is brought down to the device rate, an unknown device rate falls back to 48 kHz, absurd values clamp into 8000–192000; plus DSD extension detection (case-insensitive, SAF query strings and `#` fragments, and a dot in a *directory* name is not an extension) |
| `data.netease.NeteasePlaylistIdsTest` | 7 | where a playlist's track ids come from: listing entries carry **a track count but no ids**, and only the detail response supplies the complete ordered list; `trackIds` wins over the inline sample, older shapes fall back to inline `tracks`, non-positive ids are dropped from either source, an empty playlist yields nothing, and truncation is detectable against `trackCount` |
| `ui.theme.AuroraPaletteTest` | 4 | desaturation and greying are pure numeric transforms, so they can be checked without a display: each of the four stops (accent / deep / glow / blush) of all nine themes must come out **less saturated** than the raw colour; contrast on a white surface must not drop (every stop actually gains, the weakest being Borealis at 2.54 → 3.14), on the dark surface a small dip is allowed but the result must stay ≥ 4:1 (the widest dip is Borealis, 7.60 → 6.07); `gradientStops` must be built from the `soft*` entries rather than the raw enum colours — reading the raw ones would leave the gradients and artwork placeholders neon while only the schemes turned grey — and repeated reads must be stable |
| `data.local.LyricDecodeTest` | 8 | the decoded sidecar entry point `decodeLyricBytes()`: UTF-8 with and without a BOM, UTF-16 with a BOM, BOM-less UTF-16 sniffed from its NUL pattern, a real GB18030 paragraph (the fixture is asserted to be invalid UTF-8, so the case cannot silently degenerate into a no-op), plain ASCII and empty input; plus the same timestamped Chinese lyric read back identically from UTF-8, GB18030 and UTF-16LE. The order matters: strict UTF-8 before GB18030, otherwise a Chinese `.lrc` becomes either mojibake or a dropped sheet that looks as if the user picked no file |
| `playback.DecodeCapabilityTest` | 11 | capability detection on Android: **Opus is decodable here** (media3), WMA is blocked both by extension and by its ASF GUID, APE is blocked, DST-compressed DFF is distinguished from plain DSD, a DSF without its magic word reports `UNKNOWN`, `content://` and null paths are never misjudged, real-file magic sniffing and its LRU cache invalidation, and a missing file returning `UNKNOWN` rather than “unsupported” |
| `data.download.Id3v2Test` | 11 | writing an ID3v2.3 tag for MP3 and a native `VORBIS_COMMENT` + `PICTURE` block for FLAC (M4A / ALAC still get none): syncsafe frame sizes, UTF-16 text frames with a BOM, an APIC JPEG cover, frame ordering and overwrite, stripping an existing tag before writing, read-back and the strict `hasTag` header check — and the tag we write is decoded back with the project's own jaudiotagger, so “other software can read it” is asserted, not assumed |
| `data.download.FlacTagsTest` | 12 | writing a FLAC tag: STREAMINFO stays byte-identical and first, a `VORBIS_COMMENT` block with little-endian lengths and keys (empty values omitted), a type-3 `image/jpeg` `PICTURE` block, `0x80` set only on the last metadata block, a reused padding block leaving the file size unchanged, and non-FLAC input rejected outright — read back independently with jaudiotagger, with the audio frames asserted byte-identical to the input |
| `data.download.DownloadPathsTest` | 10 | download paths: sanitising illegal characters (runs of them collapse to one `_`), the `artist/album/NN - title.ext` layout, lowercase extensions with an `mp3` fallback, `(2)` suffixes when the extension is missing, and avoiding a file that already exists |
| `data.repo.NeteaseAlbumPagingTest` | 8 | favourited-album paging: cursor advance, de-duplication across pages, the `hasMore` decision, a short final page, and an empty account returning nothing instead of failing |
| `data.repo.GrantedQualityTest` | 7 | the granted-tier rule: `lossless` and `hires` are not a downgrade, `exhigh` / `standard` / empty are, case does not matter, and container plus bitrate pass through untouched — the playback page note about a missing lossless master rides on this rule, so getting it wrong would slander tracks that do have one |
| `data.model.LyricSubLineTest` | 5 | Japanese-script detection and the romanisation sub-line: kana / kanji mixed text is detected, pure Chinese produces no romanised line, and the sub-line scale is clamped to 0.5–1.0 |

Four more suites were added with the audio-quality and download work, and they guard exactly the places where a silent regression would never be noticed: `playback.DecodeCapabilityTest` (11) pins “Opus plays here, WMA / APE / DST do not, and a `content://` path or a missing file must not be mistaken for an unsupported format”; `data.download.Id3v2Test` (11) and `data.download.DownloadPathsTest` (10) pin the tag writer (syncsafe sizes, UTF-16 BOM, embedded cover) and the filename sanitiser; `data.repo.NeteaseAlbumPagingTest` (8) and `data.model.LyricSubLineTest` (5) pin the album cursor and the Japanese-script decision. The tag test does not stop at “the bytes equal what I expected” — it decodes its own output with jaudiotagger, because a self-consistent but non-conforming writer passes the first assertion and fails in every other player. Two more suites came with the Hi-Res work: `data.download.FlacTagsTest` (12) pins the FLAC writer (STREAMINFO untouched and first, a little-endian `VORBIS_COMMENT`, a `PICTURE` block, `0x80` only on the last block, padding reused so the file size does not change, non-FLAC input rejected) and reads its own output back with jaudiotagger while asserting the audio frames are byte-identical; `data.repo.GrantedQualityTest` (7) pins which tiers count as lossy, because the playback page tells the user a track has no lossless master and a wrong rule would slander the ones that do.

These tests **found three real bugs** during development: `LrcParser.formatStamp` and
`NeteaseIdentity.randomHex` both used locale-sensitive `String.format`, which on Arabic / Persian /
Bengali devices would emit non-ASCII digits — unreadable `.lrc` files and malformed request-signing
cookies (both now use an explicit hex table and `Locale.ROOT`); and `parseCookieInput` treated a lone
`;` as a bare token, minting an empty `MUSIC_U` cookie (it now only treats a *single* `=`-less
fragment as a token). `NeteaseEndpointTest` and `RequestIdTest` are retro-fitted **regression guards**:
the eapi gateway was once pointed at `interfacepc.music.163.com` and `requestId` once lacked its
separating underscore. Neither mistake breaks the build — both only surface on a real device as an
inexplicable failure — so a test is the only thing that can hold them down.

A fourth bug was **not** found by a test but by probe scripts replaying this app's own request byte
for byte: NetEase returns **cleartext `http://`** stream links, which Android 9+ refuses. That is
exactly why artwork, artist and lyrics loaded while playback did not — all three arrive over HTTPS.
`StreamUrlsTest` and `PlaybackMessageTest` were added afterwards as guards.

A fifth bug was likewise found by probing — and a user reported it first: *playlists imported after sign‑in arrive with zero songs*.
The bulk sync used to read track ids off the `/api/user/playlist` **listing**, which carries none at all
(`tracks` is `null`, `trackIds` is absent), so every playlist was written with its correct name and track
count but an empty membership. `/api/v6/playlist/detail` returns the complete `trackIds` at **any** `n` (a
965-track playlist is 965 at `n` = 0/1/10/1000/100000) while its inline `tracks` is always truncated to 10
— so the old `n=100000` inflated the response and bought nothing. `NeteasePlaylistIdsTest` now pins the
difference between the listing shape and the detail shape, and a second, independent defect was fixed with
it: nothing used to sync after sign‑in at all. Each account now imports automatically the first time it
becomes signed in, and the one‑shot marker is dropped again if the import fails, so a retry is possible.

### Architecture

Single activity + `AppContainer` (hand‑written DI) · `SQLiteOpenHelper` DAOs (no Room) ·
`SharedPreferences` settings (no DataStore) · OkHttp + kotlinx.serialization · Media3 ·
Miuix `NavDisplay` with a single back stack · a `Strings` catalog of ~260 bilingual entries.
`data.netease.StreamUrls` upgrades NetEase CDN links from cleartext to TLS, and
`data.model.LyricProgress` holds the pure karaoke-progress maths behind the lyric scroll, and
`trackIdList` in `data.netease` is the single place that decides whether a playlist's ids come from
`trackIds` or from the inline sample.

Room, Hilt and DataStore are deliberately absent: hand‑written replacements remove every
annotation‑processor version risk from the build.

### Known limitations

- The NetEase endpoints are **unofficial** and can break at any time. Only `api` and `eapi` are used
  (`weapi` returns a 200 with an empty body).
- **Sign‑in is subject to NetEase risk control.** On some networks the service answers *every* login
  route — QR polling and password alike — with `-462`, meaning "solve a captcha first". The QR tab
  then shows the verification link instead of spinning forever.
  Measured behaviour on the build machine: five deliberately different client fingerprints (web vs
  mobile UA, `os=pc` vs `os=android` cookies, `music.163.com` vs `interface.music.163.com` vs
  `interfacepc.music.163.com`, and sending no cookies at all) all produced the *identical* `-462`
  response, and a deliberately fake password was gated the same way. So the block is at the egress‑IP
  level, not a case of this app faking its fingerprint badly. If your device hits it too, use one of
  the **two paths that never touch the login endpoints**: (1) **browser sign‑in** — switch to the
  "Browser" tab, log in on `music.163.com/m/login` inside the app, and the `MUSIC_U` cookie is
  harvested automatically; (2) **Cookie sign‑in** — sign in at `music.163.com` in any browser, copy
  `MUSIC_U` (optionally `__csrf`) and paste it into the app. **Be aware** that repeatedly retrying QR
  or password sign‑in can trigger a stricter security check or even a temporary account freeze (3–7
  days has been reported in the community; that figure is second‑hand).
- VIP / region‑locked tracks have no stream URL; they are skipped with a toast rather than queued and
  failing later.
- NetEase returns **cleartext `http://`** stream links, and Android 9 (API 28) and later refuse
  cleartext by default — this app targets 37, which is exactly why artwork, artist and lyrics loaded
  while playback failed. HexNC upgrades the scheme to `https://` **only** on NetEase's own domains
  (`126.net` / `163.com` and their subdomains); probing confirmed the very same URLs return `206` +
  `audio/mpeg` over TLS. A `network_security_config` ships alongside it that still **forbids**
  cleartext by default and only whitelists those two domains as a last resort, so a link that somehow
  cannot be upgraded is not simply blocked by the platform. Failures are now split into “blocked
  cleartext / server unreachable / file missing / format unsupported / undecodable”.
- `/api/register/anonimous` (anonymous sign‑in) is dead and therefore unused.
- Release builds **do not run R8**, so that no keep rule goes into the APK without on‑device testing.
- **Downloads only apply to NetEase-sourced songs** that carry a `neteaseId`. The quality comes from
  Settings, and a track with no copyright or no such tier is reported with its reason instead of a
  0-byte file.
  **MP3 and FLAC files get tags; M4A / ALAC still do not** — ID3v2.3 for MP3 and a
  native `VORBIS_COMMENT` + `PICTURE` block for FLAC, which rewrites metadata only (the audio frames
  are copied byte for byte). Rewriting the M4A `ilst` atom would be another container surgery, and
  this round skipped it on risk/benefit grounds. The extension is always correct and the sidecar
  `.lrc` still lands next to the audio. On Android 10+ files go through MediaStore (`Music/HexNC`,
  reserved with `IS_PENDING` and rolled back on failure); **there is no resume** — an interrupted
  download leaves a `.part` file that is deleted, and a retry starts over.
- **How strong the format-matrix evidence is:** Android decides with media3's `MimeTypes` plus magic
  sniffing — Opus is treated as decodable, WMA / APE / DST as not. But this environment **has no
  Android runtime**, so "did media3 really decode this particular Opus file on this particular phone"
  is only confirmed at the level of the compiler and the unit tests (`DecodeCapabilityTest` pins the
  decision logic, not the codec's behaviour). The desktop half of the matrix was actually decoded —
  see `format-matrix.md` in the Windows repository.
- **This app has never been executed on a device or emulator** — the development environment has no
  Android runtime. Verification was a complete compile, 248 JVM unit tests, and the generation of a
  signed APK. Nine things therefore remain unproven here: that solving the captcha really does resume
  the QR poll (needs a device and a real account); that the in‑app browser sign‑in page renders and
  completes on a real device; that playback really does run end to end once the links are HTTPS
  (the probes prove only that the same URLs return `206` and `audio/mpeg` over TLS), along with how the
  lyric scroll actually feels; that the automatic post‑sign‑in import really writes playlist
  membership into the local database and that the new **“NetEase albums”** section really reads the
  account's favourited albums (needs a device and a real account — the probes prove only that the
  detail endpoint hands back a complete, ordered id list, and that `/api/album/sublist` really reads
  the albums — November 2026 found and fixed the reason it never had: the album array arrives under the
  top-level `data` key while the model declared `albums`, so every decode was empty; with
  `@SerialName("data")` the desktop build measures 46 albums against a real account, and Android shares
  the same model and the same decode-only test — but whether this account sees the same list on a real
  device still needs a real device); that a 192 kHz‑and‑above track really does
  play once resampled (the decision logic is unit‑tested, but which rates a given device's `AudioTrack`
  actually accepts needs hardware); and what the local‑lyric hit rate really is — since Android 13 the
  media store no longer exposes non‑media files such as `.lrc` to third‑party apps, so the persisted
  folder tree is the only reliable route and whether a sidecar is found depends on the user having
  imported that folder (which is why “Scan local lyrics” exists); and what the online lyric hit rate
  and quality really are — the matching rules, thresholds and response parsing are unit‑tested, but
  whether NetEase or LRCLIB actually has a given track, and whether the match is the same recording,
  can only be verified on a device with a network (and NetEase search is itself rate‑limited and
  risk‑controlled); and how DSD really plays — the container parsing and half‑band decimation carry 11
  unit tests, but `DsdTranscoder` depends on `Context.cacheDir` and `contentResolver` (this repo has no
  Robolectric or instrumented tests), so the cache write, the `.part` rename, the cleanup of a damaged
  file, the 512 MB prune and whether ExoPlayer accepts the transcoded WAV can only be checked on
  hardware; and how the redesigned interface actually looks and feels — the playback detail page's
  columns and stacked sections, the left navigation rail on a wide window, the row‑end `⋮` button,
  the 1120 dp reading width (the wide playback detail page is now capped and centred at that same
  width) and the **desaturated palette** were only ever compiled, the palette with numeric colour
  assertions behind it (this repo has no Compose UI or instrumented tests), so whether 0.56 / 0.44 of a
  phone's height is enough for player and lyrics, or whether a short screen needs different numbers,
  needs a device or emulator.

---

## 许可 / License

仅供学习与个人使用。网易云音乐的所有内容版权归其各自权利人所有。
For study and personal use only. All NetEase Cloud Music content remains the property of its owners.
