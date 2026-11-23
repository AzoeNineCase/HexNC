# HexNC

网易云音乐 + 本地曲库的播放器，同一套数据层与界面代码的两个平台实现：

| 目录 | 平台 | 播放层 |
| --- | --- | --- |
| [`android/`](android/) | Android（minSdk 26 / targetSdk 37） | media3 ExoPlayer + `MediaSessionService` |
| [`windows/`](windows/) | Windows 桌面（Compose Desktop） | Java Sound + SPI 解码器 |

> A NetEase Cloud Music + local-library player with two platform builds of the same data and UI
> layers: `android/` (media3/ExoPlayer) and `windows/` (Compose Desktop, Java Sound).

## 下载 / Downloads

见 [Releases](https://github.com/AzoeNineCase/HexNC/releases)：

| 文件 | 平台 | 说明 |
| --- | --- | --- |
| `HexNC-1.2.0-release.apk` | Android | 推荐；由项目自签名密钥签名，可直接安装 |
| `HexNC-1.2.0-debug.apk` | Android | 调试版，包名带 `.debug` 后缀，可与正式版共存 |
| `HexNC-1.2.0.msi` | Windows | WiX 安装包（约 108.0 MiB），无需本机预装 Java；可从开始菜单卸载 |

安卓用 `adb install -r HexNC-1.2.0-release.apk`，或把 APK 传到手机点击安装；Windows 双击 MSI 即可，
装新版会先卸掉旧版、只留一个「应用」条目。

## 功能概览

- **网易云**：扫码 / 手机号密码 / Cookie 三种登录，歌单同步（含**收藏专辑**，在「网易云专辑」分组里按需导入）、
  搜索、直链播放与落盘缓存。
- **本地曲库**：扫描本地音乐（安卓走 MediaStore + SAF，Windows 扫描 `音乐` 目录或自选文件夹），
  自动维护「全部本地音乐」歌单，歌曲、专辑、歌手、收藏、排序与批量操作；「最近播放」记录最近 24 首，
  「最近添加」只列新增歌曲且不再占满整页。
- **歌词**：LRC / 逐字 yrc / 翻译 / 罗马音解析与排版，可调字号与行距；本地找不到歌词时会到
  **网易云与 LRCLIB** 在线搜索，匹配度足够高才自动导入，否则给出候选列表由你挑选；
  无时间轴的纯文本歌词以整篇列表呈现，不做假滚动。
- **播放**：顺序 / 循环 / 单曲 / 随机，失败自动跳过并说明原因（文件丢失、格式不支持、设备不可用、
  权限不足各自独立文案）；**DSD（`.dsf`/`.dff`）内置纯 Kotlin 解码器**（DSF/DFF 解析 + 半带 FIR 抽取到
  176.4 kHz PCM，安卓端转码后交给 ExoPlayer），只有 DST 压缩与结构损坏的文件才提示后跳过；
  输出优先 24-bit 直通，降到 16-bit 时走 TPDF dither，采样率用 Hann 窗 sinc 重采样，声道与限幅都做齐。
- **格式支持**：桌面端 mp3 / flac / wav / ogg / m4a(AAC·ALAC) / ape / dsf / dff 实测可播，
  opus 与 wma 明确标注「不支持」并在列表里打徽标（不再等点开才失败）；安卓端 media3 另外支持 opus。
  完整的 12 行格式矩阵与实测记录见 [`windows/format-matrix.md`](windows/format-matrix.md)。
- **下载**：网易云来源的歌曲可以落成真实音频文件（`歌手/专辑/曲序 - 歌名.ext`，最多两个并发，
  重复自动加 `(2)`），默认请求 Hi-Res，拿不到无损母带时如实回落 320k MP3 而不是改名冒充；
  MP3 写 ID3v2.3、FLAC 写原生 Vorbis comment，封面与 `.lrc` 一起写入；面板会汇总实际落盘的容器
  （如 `FLAC ×2 · MP3 ×1`）与多少首没有无损版权。
- **界面**：Miuix 0.9.4 + 极光渐变主题，深色 / 浅色 / AMOLED、动态取色、跟随封面取色；整体做了
  降饱和处理，宽屏播放页与列表页对齐在同一列宽内居中；播放页把进度条沉到最底部做成细长条、音量收进
  「播放选项」弹层、音质与格式只在歌曲信息面板里显示，并新增只显示封面与滚动歌词的**沉浸模式**；
  列表用 7 行骨架屏代替转圈，时间用等宽数字避免每秒抖动。
- **图标**：双端共用一枚「实心渐变六边形 + 挖空播放键」的标记；Windows 端此前没有 `.ico`，
  现在 `iconFile` 编进 `HexNC.exe`、窗口图标也从同一张图设置，任务栏与 Alt-Tab 不再显示 Java 咖啡杯。
- **Windows 安装包**：MSI 支持覆盖安装（自动先卸旧版、只留一个条目）与从开始菜单直接卸载。

各自的详细说明、工具链版本与实现笔记见 [`android/README.md`](android/README.md) 与
[`windows/README.md`](windows/README.md)。

## 构建

两个子项目都是独立的 Gradle 工程，仓库**不含 gradle wrapper**，请使用 JDK 21 + Gradle 9.8（安卓端另需
Android SDK / AGP 9.4.1）。

```bash
# Android
cd android && gradle :app:assembleDebug        # 或 :app:assembleRelease（需自备签名，见其 README）

# Windows
cd windows && gradle -p . :test && gradle -p . :packageMsi
```

## 测试

| 平台 | 规模 | 命令 |
| --- | --- | --- |
| Android | **248 个 JVM 单元测试 / 25 个测试类** | `gradle :app:testDebugUnitTest` |
| Windows | **294 个 JVM 单元测试 / 32 个测试类** | `gradle -p . :test` |

两端共用同一套测试源码，全部为 JUnit 4，无需模拟器或真机。

## 目录结构

```
android/     Android 应用（Gradle 工程，app/ 模块 + gradle/libs.versions.toml）
windows/     Windows 桌面应用（Gradle 工程，src/main/kotlin + 兼容外壳）
```

## 签名与密钥

仓库**不包含**任何私钥：`*.jks` / `keystore.properties` 等均在 `.gitignore` 中。Release 里的
release APK 由作者的自签名密钥构建（证书主体 `CN=HexNC`）；要从源码构建可安装的 release 包，请
自备密钥并在 `android/` 下创建 `keystore.properties`，详见 [`android/README.md`](android/README.md)。

## 免责声明

- 本项目仅供**学习与个人使用**，不提供、不托管、不分发任何音乐内容，播放内容来自用户自己的
  本地文件或用户自己的网易云账号。
- 其中的网易云接口为**第三方逆向实现**，与网易公司无关，可能违反其服务条款；请自行承担使用风险，
  不要用于商业用途或大规模请求。
- 使用第三方客户端登录存在账号被风控（临时冻结 / 强制改密）的风险；请优先使用官方客户端。

---

### English

**HexNC** is a NetEase Cloud Music + local-library player. `android/` is the Android build
(media3/ExoPlayer + `MediaSessionService`) and `windows/` is the Compose Desktop build (Java Sound
with SPI decoders); both share the same data and UI sources.

Download the installers from [Releases](https://github.com/AzoeNineCase/HexNC/releases)
(`HexNC-1.2.0-release.apk`, `HexNC-1.2.0-debug.apk`, `HexNC-1.2.0.msi`).

Highlights: three sign-in routes (QR / phone+password / cookie), playlist sync including **favourited
NetEase albums** (imported on demand from the “NetEase albums” section), an on-disk stream cache, local
library scanning with an auto-maintained “All local music” playlist, a 24-track “Recently played” shelf
and a compact “Recently added” list, LRC / word-level `yrc` / translation / romanisation lyrics with
**online completion from NetEase and LRCLIB** (imported automatically only above a high match bar,
otherwise offered as a picker), playback that reports the real failure reason (missing file, unsupported
format, device unavailable, permission denied), and **DSD (`.dsf`/`.dff`) playback through a built-in
pure-Kotlin decoder** (only DST-compressed or malformed files are skipped). Output goes out as 24-bit when
the device takes it, falls back to 16-bit with TPDF dither, resamples through a Hann-windowed sinc, and the
supported-format matrix (`windows/format-matrix.md`) is honest about what does not work: Opus and WMA on the
desktop, WMA/APE/DST on Android. NetEase-sourced songs can be **downloaded as real audio files** — MP3 with
an ID3v2.3 tag, FLAC with a native Vorbis comment, cover plus `.lrc` alongside — and the download level
defaults to Hi-Res, falling back to 320k MP3 (never a renamed file) when the track has no lossless master.
The player page keeps the seek bar docked at the bottom as a thin rule, hides volume in a “playback options”
sheet, shows quality and format only inside the song-info panel, and adds an **immersive mode** with just
the artwork and scrolling lyrics. Both platforms now share one icon, and the Windows build finally ships
its own `.ico` (compiled into `HexNC.exe` and set as the window icon) instead of the Java cup. On Windows
the MSI supports overwrite-installs (the older version is removed first, leaving one Apps entry) and
uninstalls straight from the Start menu. See the per-platform READMEs for details.

Build with JDK 21 + Gradle 9.8 (no wrapper is shipped; the Android side also needs the Android SDK and
AGP 9.4.1):

```bash
cd android && gradle :app:assembleDebug
cd windows && gradle -p . :test && gradle -p . :packageMsi
```

Tests: **248 JVM unit tests in 25 classes** on Android (`gradle :app:testDebugUnitTest`) and
**294 JVM unit tests in 32 classes** on Windows (`gradle -p . :test`), all JUnit 4 — no emulator or
device required.

No private keys are committed (`*.jks` / `keystore.properties` are git-ignored); the release APK on the
Releases page was signed with the author's self-signed key (`CN=HexNC`). Bring your own key to build
an installable release from source.

**Disclaimer** — this project is for study and personal use only. It ships no music content; playback
comes from your own local files or your own NetEase account. The NetEase endpoints are a third-party
reverse-engineered implementation, unaffiliated with NetEase, possibly contrary to its terms of
service, and should not be used commercially or for bulk requests. Signing in with a third-party client
carries a risk of account risk-control (temporary freeze / forced password reset) — prefer the official
client.
