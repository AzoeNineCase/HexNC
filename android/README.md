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
| 歌单同步 | **登录后自动导入一次**（红心歌单、自建歌单、收藏歌单），歌单页也保留「同步全部」按钮与单歌单下拉同步。列表接口 `/api/user/playlist` 只给歌单元数据、**不含歌曲 ID**（`tracks` 为 `null`，也没有 `trackIds` 字段），所以每个歌单的歌曲 ID 都单独向 `/api/v6/playlist/detail` 取（实测该接口在任意 `n` 下都返回完整 `trackIds`，只有内联 `tracks` 被限制在 10 条），再批量拉取歌曲详情写入本地；**抓取失败或 ID 数少于 `trackCount` 的歌单会被跳过而不是清空**，以免一次网络抖动删掉本地已有的歌曲 |
| 曲库 | 歌曲 / 专辑 / 艺术家 / 歌单 本地索引，收藏、最近添加 |
| 本地导入 | **系统媒体库扫描**（一键导入手机里已有的音乐）与**文件夹选择器**批量扫描 mp3/flac/m4a/aac/ogg/opus/wav/wma/ape/alac/mp4/3gp/dsf/dff；同名 `.lrc` 自动配对——优先找音频文件同目录的旁挂文件，再查 MediaStore 里的 `.lrc` 索引，最后遍历用户授权过的文件夹树；选择文件夹时会**持久化 SAF 授权**，重启后文件夹里的歌仍可读取 |
| 全部本地音乐 | 自动维护一个**「全部本地音乐」歌单**：每次导入本地歌曲后，把所有本地音乐并入其中（曲库页只展示少量「最近添加」，一首歌想被找到就不能只靠它）；该歌单不可重命名 / 删除，名称随语言切换 |
| 歌词 | 导入单曲 `.lrc`、网易云 lrc / tlyric（翻译）/ romalrc（音译）/ yrc（逐字）；**本地歌曲打开歌词页时会自动寻找旁挂 `.lrc`**（无需手动操作），设置页另有「扫描本地歌词」用于给旧曲库批量补全；**本地找不到歌词时会再向网易云与 LRCLIB 联网搜索**，只有标题、艺术家、时长三项综合得分 ≥ 0.86 且标题相似度 ≥ 0.90 才自动导入，达不到就只在歌词页给出「搜索歌词」入口，由用户从候选列表里自己挑（宁可留空，也不能贴错歌词）；无时间戳的纯文本歌词按**静态行**显示，而不是被当成「无效歌词文件」丢弃；解析换行、多时间戳、`[mm:ss:xx]` 变体与 offset |
| 歌词排版 | 字号（12–48sp）、行距、翻译字号比例、翻译与音译开关、实时预览 |
| 歌词滚动 | 当前行自动**居中**跟随进度；有逐字歌词（yrc）时按字长比例做卡拉 OK 渐变扫过，否则逐行推进（单行最多按 8 秒铺满）；手动拖动歌词后 5 秒自动恢复跟随，也可点「跟随歌词」立即恢复；点击任意歌词行可跳转到该句；**无时间戳的纯文本歌词**只静态展示，不参与跟随、不显示高亮行，也不响应点击跳转 |
| 自定义封面 | 单曲封面、整张专辑封面、歌单封面，均可本地图片替换或一键恢复默认 |
| Playlist | 新建 / 重命名 / 删除歌单、加入 / 移出歌曲、拖动排序、播放全部 / 随机播放 |
| 播放 | Media3 ExoPlayer + MediaSessionService（通知栏、蓝牙按键、锁屏可控），顺序 / 列表循环 / 单曲循环 / 随机；网易云直链是**明文 HTTP**，会被 Android 9+ 拦截，因此播放地址统一升级为 HTTPS，并按「明文被拦 / 网络不通 / 文件失效 / 格式不支持 / 无法解码」分别提示失败原因；**192 kHz 及以上采样率**会在进入 `AudioTrack` 之前用 `SonicAudioProcessor` 重采样到设备原生采样率（否则 `AudioTrack` 初始化失败，用户看到的只是"无法解码"）；**DSD（`.dsf`/`.dff`）** Android 没有解码器，播放时明确提示并跳过，而不是给一个含糊的读取错误 |
| 搜索 | 歌曲、专辑、艺术家、歌单四类搜索；点按即可把网易云内容导入本地并播放 |
| 界面 | 迷你播放条、全屏播放页、滚动歌词页、播放队列面板 |

### 自设计图标

`ic_launcher_foreground.xml` 是手绘矢量图：一个尖顶六边形环（象征"网络 / 云端"），内部嵌播放三角，两者都填充**极光渐变**
`#36D1DC → #7B5CFF → #FF5FA2`。同时提供单色版（monochrome）以适配 Android 13+ 主题化图标。

### 安装

从 [Releases](https://github.com/AzoeNineCase/HexNC/releases) 下载：

```
HexNC-1.0.0-release.apk     ← 推荐：由项目自签名密钥签名，可直接安装
HexNC-1.0.0-debug.apk       ← 调试版（包名带 .debug 后缀，可与正式版共存）
```

`adb install -r HexNC-1.0.0-release.apk`，或直接把 APK 传到手机上点击安装。

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
# 本机一键脚本（路径按自己的环境修改；仓库里没有附带 gradle wrapper）
D:\AI Project\.toolchain\build.cmd :app:assembleDebug
D:\AI Project\.toolchain\build.cmd :app:assembleRelease
```

或使用任意 Gradle 9.8 + JDK 21（仓库不含 gradle wrapper，可用本机 Gradle，或先跑
`gradle wrapper --gradle-version 9.8` 自行生成）：

```bash
gradle :app:assembleRelease
```

**签名**：本仓库**不包含**发布密钥与 `keystore.properties`（`.jks`、`keystore.properties`
都在 `.gitignore` 里），Release 中的 release APK 由作者的自签名密钥构建。要从源码产出可安装的
release 包，请自备密钥并在仓库根目录创建 `keystore.properties`：

```properties
storeFile=keystore/your-release.jks
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

缺少该文件时 `assembleRelease` 会退化为未签名构建，而未签名 APK 无法直接安装；只想本机试用请
改用 `:app:assembleDebug`。

### 测试

由于开发环境没有 Android 运行时，**单元测试是除编译器之外唯一的自动化验证手段**，全部跑在 JVM 上（JUnit 4，无 Robolectric）：

```powershell
D:\AI Project\.toolchain\build.cmd :app:testDebugUnitTest
```

共 **157 个测试 / 15 个测试类，全部通过**：

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
| `playback.PlaybackMessageTest` | 6 | ExoPlayer 错误码 → 用户可见提示的映射：明文被拦、网络失败、文件失效 / 无权限、格式或解码不支持、其他错误各自独立，未知错误码不再被当成提示文案直接弹给用户 |
| `playback.AudioResamplingTest` | 7 | 重采样判定：不高于设备采样率时**必须原样直通**（44.1 kHz 在 48 kHz 设备上不重采样）、高于时降到设备采样率、设备采样率未知时回退 48 kHz、异常值收敛到 8000–192000；以及 DSD 扩展名识别（含大小写、SAF 查询串与 `#` 片段，且不会把目录名里的点当成扩展名） |
| `data.netease.NeteasePlaylistIdsTest` | 7 | 歌单歌曲 ID 的来源：列表接口的条目**只有歌曲数、没有 ID**，详情接口才给出完整有序 ID；`trackIds` 优先于内联样例、旧结构回退到内联 `tracks`、非正 ID 被丢弃、空歌单返回空、可用 `trackCount` 检出截断 |

这些测试在开发过程中**真的抓到了三个 bug**：`LrcParser.formatStamp` 与 `NeteaseIdentity.randomHex` 都使用了区域设置相关的 `String.format`，在阿拉伯语 / 波斯语 / 孟加拉语设备上会写出非 ASCII 数字，导致歌词文件不可读、请求签名 Cookie 非法（已改为显式十六进制表 / `Locale.ROOT`）；另外 `parseCookieInput` 会把一个孤立的 `;` 当成裸 token，生成名为 `MUSIC_U` 的空值 Cookie（已改为仅当输入是单个片段时才视作裸 token）。`NeteaseEndpointTest` 与 `RequestIdTest` 属于事后补上的**防回归**测试：eapi 网关地址曾被误写成 `interfacepc.music.163.com`，`requestId` 也曾缺少分隔下划线——这两类错误都不会让编译失败，只会在真机上表现为"说不清的异常"，所以必须用测试钉住。另有第四个 bug 不是测试抓到的，而是用探针脚本复刻 App 的请求定位的：网易云返回的播放直链是明文 `http://`，被 Android 9+ 的明文流量策略拦下——这正是「封面、艺术家、歌词都正常，唯独播放失败」的原因（三者都走 HTTPS）。修完之后补上了 `StreamUrlsTest` 与 `PlaybackMessageTest` 作为护栏。

第五个 bug 同样是探针发现的，而且是用户先报上来的：「登录后歌单是导进来了，但歌单里一首歌都没有」。原因是批量同步当时从 `/api/user/playlist` 的**列表条目**里读歌曲 ID，而那个接口根本不返回 ID（`tracks` 是 `null`，也没有 `trackIds` 字段），于是每个歌单都拿到了正确的歌单名与歌曲数，成员列表却被写成空。详情接口 `/api/v6/playlist/detail` 在**任意 `n`** 下都返回完整 `trackIds`（965 首的歌单在 `n` = 0/1/10/1000/100000 下都是 965），内联 `tracks` 则永远被截断到 10 条——所以旧代码里的 `n=100000` 除了把响应体撑大之外没有任何作用。`NeteasePlaylistIdsTest` 现在把「列表形状」与「详情形状」的区别钉死，同时修好了「登录后并不自动导入」这个独立缺陷（现在每个账号只在登录态首次出现时自动导入一次，失败会撤销标记以便下次重试）。

### 架构

```
com.hexnc.music
├─ MainActivity / HexNCApp         单一 Activity + 进程级 AppContainer
├─ di.AppContainer                 手写依赖注入（无 Hilt/KSP）
├─ data
│  ├─ model        Song / Playlist / Lyrics / Ids / LyricProgress
│  ├─ db           SQLiteOpenHelper + 手写 DAO（无 Room）
│  ├─ local        LrcParser、CoverStore、LocalScanner(SAF)
│  ├─ netease      eapi 加密、Cookie、API、模型、Identity、StreamUrls（明文→HTTPS）、trackIdList（歌单 ID 提取）
│  ├─ prefs        SharedPreferences 设置
│  └─ repo         LibraryRepository（本地） / NeteaseRepository（云端）
├─ playback        PlaybackService（Media3）+ PlaybackController（MediaController）
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
- 网易云返回的播放直链是**明文 `http://`**；Android 9（API 28）起系统默认禁止明文流量，而本项目 targetSdk 37，所以**能读到封面/艺术家/歌词，却无法播放**。HexNC 只在网易云自家域名（`126.net` / `163.com` 及其子域）上把协议升级为 `https://`（实测同一批 URL 走 TLS 能正常返回 `206` + `audio/mpeg`），并附带一份 `network_security_config`：默认仍**禁止**明文，只给这两个域名留一条兜底白名单，以免升级不了的直链被平台直接拦死。播放失败时会区分「明文被拦 / 连不上服务器 / 文件失效 / 格式不支持 / 无法解码」五种提示；扫描出来的 DSD（`.dsf`/`.dff`）在 Android 上没有解码器，会明确提示并跳过，而不是抛一个含糊的读取错误。
- 网易云 `/api/register/anonimous`（匿名登录）已失效，因此未启用。
- 构建产物 `assembleRelease` **未开启 R8 混淆**，以避免未经真机验证的 keep 规则导致运行期崩溃。
- 本项目**未在真机 / 模拟器上运行过**：当前开发环境没有可用的 Android 运行时。验证方式是完整编译 + 157 个 JVM 单元测试 + 生成已签名 APK。
- 因此有七件事**无法在本环境证明**：一是通过人机验证后扫码轮询是否真的恢复（需要真机 + 真实账号）；二是 App 内浏览器登录页在真机上能否正常渲染并走完登录流程；三是播放直链改为 HTTPS 后在真机上是否能完整播放（探针只能证明同一批 URL 走 TLS 会返回 `206` 与 `audio/mpeg`），以及歌词滚动的实际观感；四是登录后的自动导入是否真的把歌单成员写进本地库（需要真机 + 真实账号，探针只能证明详情接口返回的 ID 列表完整）；五是 192 kHz 以上歌曲经重采样后能否真的播放——判定逻辑有单元测试，但 `AudioTrack` 在具体机型上真正接受哪些采样率只能真机验证；六是**本地歌词的实际命中率**：Android 13+ 起媒体库对非媒体文件（`.lrc`）不再对第三方应用可见，此时只能依赖用户授权过的文件夹树，能否找到取决于用户是否用「导入文件夹」导入过（因此设置页保留了「扫描本地歌词」用于补扫）；七是**在线歌词的实际命中率与歌词质量**——匹配规则、阈值与响应解析都覆盖了单元测试，但"网易云 / LRCLIB 能否搜到这首歌、搜到的是不是同一版录音"只能联网真机验证，且网易云搜索本身还要过风控与频控。

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
| Playlist sync | **Imports once automatically after sign‑in** (the liked playlist, your own playlists and subscribed ones); the one‑tap “sync all” button and per‑playlist refresh remain. The `/api/user/playlist` listing returns metadata only and **no track ids at all** (`tracks` is `null` and `trackIds` is absent), so each playlist's ids are fetched separately from `/api/v6/playlist/detail` (probed: a complete `trackIds` list at any `n`, while the inline `tracks` array is capped at 10), and the songs are then hydrated through `songDetail`; **a playlist whose fetch fails, or whose id count is below its `trackCount`, is skipped rather than emptied**, so one network hiccup cannot delete songs already stored locally |
| Library | Local index over songs / albums / artists / playlists, with favourites and recently added |
| Local import | **MediaStore scan** (one tap, imports the music already on the phone) and a **folder picker** via SAF; scans mp3/flac/m4a/aac/ogg/opus/wav/wma/ape/alac/mp4/3gp/dsf/dff and pairs sidecar `.lrc` files — the audio file's own directory first, then a MediaStore index of `.lrc` names, then every folder tree the user has granted; the SAF grant is **persisted**, so those folders stay readable after a restart |
| All local music | An auto‑maintained **“All local music” playlist**: every local import merges all local songs into it (the library tab only shows a handful of “recently added”, so a song with no playlist of its own is otherwise unreachable); it cannot be renamed or deleted, and its title follows the app language |
| Lyrics | Single `.lrc` import; NetEase lrc / tlyric / romalrc / yrc; **local songs find a sidecar `.lrc` automatically when the lyric pane opens**, and Settings offers “Scan local lyrics” to back‑fill an existing library; **when nothing local matches, it searches NetEase and LRCLIB online** and imports automatically only when title, artist and duration together score ≥ 0.86 with a title similarity ≥ 0.90 — below that the lyric page merely offers a “Search lyrics” picker so the user chooses from the candidates (an empty pane beats the wrong lyrics); an untimed plain‑text file is shown as static lines instead of being discarded as invalid; multi‑timestamp, `[mm:ss:xx]` variant and offset parsing |
| Lyric layout | Font size (12–48 sp), line spacing, translation scale, translation & romanisation toggles, live preview |
| Lyric scroll | The active line auto‑centres; with word‑level lyrics (yrc) a karaoke wipe sweeps each word in proportion to its length, otherwise it advances line by line (capped at 8 s per line); a manual drag pauses following for 5 s and a “Follow lyrics” button restores it instantly; tapping any line seeks to it. An **untimed plain‑text** sheet is shown statically — no following, no highlighted line, no seek on tap |
| Custom covers | Per‑song, per‑album and per‑playlist covers from any local image, with one‑tap reset |
| Playlists | Create, rename, delete, add, remove, reorder, play all, shuffle |
| Playback | Media3 ExoPlayer + `MediaSessionService` (notification, Bluetooth, lock screen); in‑order / repeat all / repeat one / shuffle. NetEase hands out **plain‑HTTP** stream links, which Android 9+ blocks, so they are upgraded to HTTPS and failures are reported as “blocked cleartext / network unreachable / file missing / format unsupported / undecodable”. **192 kHz and above** is resampled with `SonicAudioProcessor` down to the device's native rate before it reaches `AudioTrack` (otherwise `AudioTrack` fails to initialise and the user only ever sees “undecodable”); **DSD (`.dsf`/`.dff`)** has no decoder on Android and is reported and skipped instead of failing with an opaque read error |
| Search | Songs, albums, artists and playlists; tapping imports the NetEase item locally and plays it |
| Chrome | Mini player, full‑screen now‑playing page, auto‑scrolling lyrics page, queue sheet |

### The icon

`ic_launcher_foreground.xml` is hand‑drawn vector art: a pointy‑top hexagonal ring (the “cloud /
network” cue) with a play triangle inside, both filled with the aurora gradient
`#36D1DC → #7B5CFF → #FF5FA2`. A monochrome variant ships for Android 13+ themed icons.

### Install

Download from [Releases](https://github.com/AzoeNineCase/HexNC/releases):

```
HexNC-1.0.0-release.apk     ← recommended; signed with the project's self-signed key, directly installable
HexNC-1.0.0-debug.apk       ← debug build (package id ends in .debug, installs side by side)
```

`adb install -r HexNC-1.0.0-release.apk`, or copy the APK to the phone and tap it.

### Build

The project pins a toolchain combination that has actually been compiled end to end:

JDK 21 (Temurin) · Gradle 9.8.0 · AGP 9.4.1 · Kotlin 2.4.20 · Compose Multiplatform 1.12.1 ·
Miuix 0.9.4 · compileSdk/targetSdk 37 · minSdk 26.

```powershell
# machine-specific helper script (edit the path for your environment;
# the repository does not ship a gradle wrapper)
D:\AI Project\.toolchain\build.cmd :app:assembleDebug
D:\AI Project\.toolchain\build.cmd :app:assembleRelease
```

**Signing** — the repository **does not** contain the release keystore or `keystore.properties`
(both `.jks` and `keystore.properties` are in `.gitignore`); the `release` APK on the Releases page
was built with the author's self-signed key. To produce an installable release build from source,
bring your own key and create `keystore.properties` at the repo root:

```properties
storeFile=keystore/your-release.jks
storePassword=your-password
keyAlias=your-alias
keyPassword=your-password
```

Without that file `assembleRelease` falls back to an unsigned build, and an unsigned APK cannot be
installed; use `:app:assembleDebug` if you only want to try it locally.

### Tests

Because the environment has no Android runtime, **unit tests are the only automated verification
besides the compiler**. They run on the JVM only (JUnit 4, no Robolectric):

```powershell
D:\AI Project\.toolchain\build.cmd :app:testDebugUnitTest
```

**157 tests in 15 classes, all passing:**

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
| `playback.PlaybackMessageTest` | 6 | the ExoPlayer error-code → user-facing message mapping: blocked cleartext, network failure, **a missing or unreadable file**, and **an unsupported container / codec / sample rate** all stay distinct, and an unknown code is no longer shown to the user as if it were a message |
| `playback.AudioResamplingTest` | 7 | the resampling decision: anything **at or below** the device rate must pass through untouched (44.1 kHz on a 48 kHz device is never resampled), anything above is brought down to the device rate, an unknown device rate falls back to 48 kHz, absurd values clamp into 8000–192000; plus DSD extension detection (case-insensitive, SAF query strings and `#` fragments, and a dot in a *directory* name is not an extension) |
| `data.netease.NeteasePlaylistIdsTest` | 7 | where a playlist's track ids come from: listing entries carry **a track count but no ids**, and only the detail response supplies the complete ordered list; `trackIds` wins over the inline sample, older shapes fall back to inline `tracks`, non-positive ids are dropped from either source, an empty playlist yields nothing, and truncation is detectable against `trackCount` |

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
- **This app has never been executed on a device or emulator** — the development environment has no
  Android runtime. Verification was a complete compile, 157 JVM unit tests, and the generation of a
  signed APK. Seven things therefore remain unproven here: that solving the captcha really does resume
  the QR poll (needs a device and a real account); that the in‑app browser sign‑in page renders and
  completes on a real device; that playback really does run end to end once the links are HTTPS
  (the probes prove only that the same URLs return `206` and `audio/mpeg` over TLS), along with how the
  lyric scroll actually feels; that the automatic post‑sign‑in import really writes playlist
  membership into the local database (needs a device and a real account — the probes prove only that the
  detail endpoint hands back a complete, ordered id list); that a 192 kHz‑and‑above track really does
  play once resampled (the decision logic is unit‑tested, but which rates a given device's `AudioTrack`
  actually accepts needs hardware); and what the local‑lyric hit rate really is — since Android 13 the
  media store no longer exposes non‑media files such as `.lrc` to third‑party apps, so the persisted
  folder tree is the only reliable route and whether a sidecar is found depends on the user having
  imported that folder (which is why “Scan local lyrics” exists); and what the online lyric hit rate
  and quality really are — the matching rules, thresholds and response parsing are unit‑tested, but
  whether NetEase or LRCLIB actually has a given track, and whether the match is the same recording,
  can only be verified on a device with a network (and NetEase search is itself rate‑limited and
  risk‑controlled).

---

## 许可 / License

仅供学习与个人使用。网易云音乐的所有内容版权归其各自权利人所有。
For study and personal use only. All NetEase Cloud Music content remains the property of its owners.
