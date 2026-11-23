# HexNC · M3 本地解码格式矩阵（实测）

生成时间：2026-11-23 · 适用版本 1.2.0（versionCode 4）

本表只回答一个问题：**用户点一首本地歌曲，能不能出声、出的是什么声、出不来的话告诉他什么**。

- 「桌面解码」= Windows（Java Sound + 已装 SPI）。证据来自真实样本实测（见文末"实测记录"）。
- 「安卓解码」= media3/ExoPlayer 内置提取器 + DSD 转码器。
- 「输出」= 该格式在两端实际送到声卡/音频轨的 PCM 形态。桌面端由 `PcmPipeline.outputPlans()` 决定（优先源采样率与源位深，设备不接受时降级），安卓端由 `AudioTrack` 能力协商。

| # | 样本 | 桌面解码 | 安卓解码 | 输出（桌面） | 不支持时的提示 |
|---|---|---|---|---|---|
| 1 | MP3 320 kbps | 支持（mp3spi） | 支持 | 44.1 kHz → PCM 16-bit 44.1 kHz（直通，不重采样） | — |
| 2 | FLAC 16-bit / 44.1 kHz | 支持（jflac） | 支持 | PCM 16-bit 44.1 kHz 直通 | — |
| 3 | FLAC 24-bit / 96 kHz | 支持（实测 24-bit 解出） | 支持 | 优先 PCM 24-bit 96 kHz；设备拒绝则 24-bit 48 kHz → 仍拒绝则 16-bit 48 kHz（TPDF dither） | — |
| 4 | ALAC / M4A | 支持（javasound-alac） | 支持 | 解析为 16/24-bit，按上述阶梯输出 | — |
| 5 | AAC（ADTS `.aac` 与 `.m4a`） | 支持（javasound-aac） | 支持 | 同上 | — |
| 6 | OGG Vorbis | 支持（vorbisspi） | 支持 | 同上 | — |
| 7 | OGG Opus | **不支持**（无纯 Java 解码器） | 支持（平台解码器） | — | 桌面：`playback_unsupported_format`（"暂不支持 Opus 音频"），列表行显示「不支持」徽标 |
| 8 | WMA / ASF | **不支持**（微软编码器不可内置） | 不支持（media3 无提取器） | — | 两端：`playback_unsupported_format`（WMA 文案）+「不支持」徽标 |
| 9 | APE（Monkey's Audio） | 支持（javasound-ape） | 不支持（media3 无提取器） | 同 16/24-bit 阶梯 | 安卓：`playback_unsupported_format`（APE 文案）+ 徽标 |
| 10 | DSF（未压缩 DSD） | 支持（内置 `DsdAudioFileReader`，半带 FIR） | 支持（`DsdTranscoder`） | DSD64 → 176.4 kHz PCM 16-bit → 设备协商 | — |
| 11 | DFF（未压缩 DSDIFF） | 支持 | 支持 | 同上 | — |
| 12 | DFF（DST 压缩） | **不支持**（半带 FIR 不处理 DST） | **不支持** | — | 两端：`playback_unsupported_format`（DST 文案）+「不支持」徽标 |
| 13 | 损坏 / 截断的 MP3 | 解码器报错 | 解码器报错 | — | `playback_decode`（"文件已损坏，或解码器读不了这段音频"） |
| 14 | 存在但无读权限的文件 | — | — | — | 桌面 `playback_access` / 安卓 `playback_access`（"没有读取该文件的权限"） |
| 15 | 音频输出设备打不开 | — | — | — | `playback_device`（"音频输出设备初始化失败"） |

> 注：APE 的桌面支持来自 `javasound-ape:1.7.7`（LGPL-2.0）。本机 ffmpeg 不带 APE 编码器，**未能生成真实 APE 样本**，因此该行是"依赖已装 + 代码路径存在"的推断，不是实测；发布说明里按推断标注。

## 错误码与文案（R2）

| 错误码 | 桌面常量 | 安卓常量 | 用户看到 |
|---|---|---|---|
| 文件不存在 | `IO_FILE_NOT_FOUND` | `ERROR_CODE_IO_FILE_NOT_FOUND` | "文件不存在或已被移动，重新扫描音乐库" |
| 无权限 | `FILE_ACCESS` | `ERROR_CODE_IO_NO_PERMISSION` | `playback_access` |
| 格式不支持 | `UNSUPPORTED_FORMAT` | `CONTAINER_UNSUPPORTED` / `DECODING_FORMAT_UNSUPPORTED` | `playback_unsupported_format` |
| 解码失败（损坏） | `DECODE_ERROR` / `DECODING_FAILED` | `CONTAINER_MALFORMED` / `DECODER_INIT_FAILED` / `DECODING_FAILED` / `FORMAT_EXCEEDS_CAPABILITIES` | `playback_decode` |
| 设备故障 | `DEVICE_ERROR` | `AUDIO_TRACK_INIT_FAILED` / `AUDIO_TRACK_WRITE_FAILED` | `playback_device` |
| 其他 | — | 其余 | 通用失败文案 |

## 实测记录

样本：3 秒 440 Hz 正弦，`ffmpeg` 生成于 `HexNC For Windows\_m3samples\`（该目录是临时产物，不入库）。

```
aac.m4a       OK encoding=AAC 44100.0 Hz 2ch 16bit
aac_adts.aac  OK encoding=AAC 44100.0 Hz 2ch 16bit
alac.m4a      OK encoding=ALAC 44100.0 Hz 2ch 16bit
flac_16_44    OK encoding=FLAC 44100.0 Hz 2ch 16bit
flac_24_96    OK encoding=FLAC 96000.0 Hz 2ch 24bit
mp3_320.mp3   OK encoding=MPEG1L3 44100.0 Hz 2ch 16bit
vorbis.ogg    OK encoding=VORBISENC 44100.0 Hz 2ch 16bit
wav.wav       OK encoding=PCM_SIGNED 44100.0 Hz 2ch 16bit
opus.ogg      FAIL UnsupportedAudioFileException: File of unsupported format
wma.wma       FAIL UnsupportedAudioFileException: File of unsupported format
```

探测方式：调用 `AudioSystem.getAudioInputStream(file)` 并读取 `AudioFormat`。**注意**：`AudioSystem.getAudioFileTypes()` 只返回 `aif/au/wav`（这些 SPI 不通过该 API 暴露），所以能力探测不能靠它，只能靠真实解码或容器魔数（`DecodeCapability` 用的就是后者）。

## 位深 / 重采样策略（R4）

- **位深**：源位深优先（16/24），从不把 16-bit 填充成 24-bit；超过 24-bit 的一律按 24-bit 输出；低于 16-bit 的补到 16-bit（Java Sound 无有符号 8-bit 输出路径）。
- **降位**：24→16 一律 TPDF dither（两个独立均匀随机之差，±1 LSB，零均值、平坦谱），绝不直接截断。
- **重采样**：仅当源采样率与设备可接受率不同才做；Hann 窗 sinc，48 taps，256 相位多相表，截止取两 Nyquist 的较小者（降采抗混叠、升采带限）。
- **声道**：单声道优先以单声道打开；设备只收立体声时同电平复制两份（不做 +6 dB 的假立体声）。
- **限幅**：重采样与增益之后的溢出按可表示范围钳制，不环绕（环绕 = 满刻度爆音）。
- 以上全部可在无音频设备的情况下单测（`PcmPipelineTest`、`AudioOutputFormatTest`）。

## 占位与遗留

- Opus / WMA（桌面）与 WMA / APE（安卓）为**明确不支持**：任务书允许"明确标注"而非硬塞依赖。要真正支持需引入原生解码器（opus 需 JNI，WMA 需 FFmpeg），体积与许可证成本远超本轮范围，未做。
- DST 压缩 DSD 同样明确不支持。
- 无缝播放（gapless）列为 P2，未做。