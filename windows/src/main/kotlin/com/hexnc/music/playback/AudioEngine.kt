package com.hexnc.music.playback

import java.io.File
import java.util.ServiceLoader
import javax.sound.sampled.spi.FormatConversionProvider
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.log10

/**
 * The desktop playback engine: Java Sound, converted to the best PCM the device accepts, and written
 * to a `SourceDataLine`.
 *
 * Output goes through [PcmPipeline]: a 24-bit source is offered 24-bit PCM at its own rate first, and
 * anything the device refuses falls back to a dithered 16-bit plan before the rate is lowered. Rate
 * changes use [SincResampler] instead of the platform's converter, so 96/176.4/192 kHz material is
 * band-limited properly rather than by whatever the mixer happens to implement.
 *
 * ExoPlayer is an Android/media3 component, so something had to replace it. Java Sound with the SPI
 * decoders on the classpath (`mp3spi` for MP3, `vorbisspi` for Ogg Vorbis, `jflac` for FLAC, plus the
 * JDK's own WAV/AIFF/AU readers) covers every format NetEase hands out — `standard`/`higher`/
 * `exhigh` are MP3 and `lossless`/`hires` are FLAC — plus the common local files.
 *
 * Playback runs on one dedicated daemon thread, never on the Compose dispatcher: audio writes block
 * for as long as the device buffer takes to drain, and doing that on `Dispatchers.Main` would freeze
 * the UI. The engine reports only *events* ([Listener]) and is polled for position, which is exactly
 * the shape a `StateFlow`-driven transport bar wants.
 *
 * Seeking is implemented by reopening the stream and skipping to the target frame. It is not the
 * cheapest possible strategy, but it is the one that cannot desynchronise: no decoder in this
 * pipeline exposes a reliable sample-accurate seek.
 */
internal class AudioEngine(private val listener: Listener) {

    internal interface Listener {
        /** The stream opened. [durationMs] is 0 when the container does not declare a length. */
        fun onStarted(durationMs: Long)

        /** The track played to its end on its own. */
        fun onEnded()

        /** The track could not be opened or decoded. */
        fun onFailure(code: Int)
    }

    /**
     * A failure reading or converting the source; reported as [PlaybackErrorCode.DECODE_ERROR].
     *
     * The distinction from [DeviceFailure] is what the UI shows: a corrupt file and an unavailable
     * output device need different advice from the user, and only the thread that hit the failure
     * knows which side of the pipeline broke.
     */
    private class DecodeFailure(cause: Throwable) : RuntimeException(cause)

    /** A failure opening or using the output line; reported as [PlaybackErrorCode.DEVICE_ERROR]. */
    private class DeviceFailure(cause: Throwable) : RuntimeException(cause)

    private val position = AtomicLong(0L)
    private val duration = AtomicLong(0L)
    private val framesRead = AtomicLong(0L)

    @Volatile private var playing = false
    @Volatile private var released = false
    @Volatile private var seekRequestMs: Long? = null
    @Volatile private var volume = 1f
    @Volatile private var line: SourceDataLine? = null
    private var worker: Thread? = null

    val isPlaying: Boolean get() = playing && !released

    fun positionMs(): Long = position.get()

    fun durationMs(): Long = duration.get()

    fun start(file: File, startMs: Long = 0L, volume: Float = 1f) {
        release()
        released = false
        playing = true
        this.volume = volume.coerceIn(0f, 1f)
        position.set(startMs.coerceAtLeast(0L))
        worker = Thread({ run(file) }, "hexnc-audio").apply {
            isDaemon = true
            start()
        }
    }

    fun play() {
        playing = true
    }

    fun pause() {
        playing = false
    }

    /** Asks the worker to reopen the stream at [ms]. */
    fun seekTo(ms: Long) {
        seekRequestMs = ms.coerceAtLeast(0L)
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        line?.let { applyGain(it) }
    }

    /**
     * Stops playback and releases the audio device.
     *
     * The line is closed from the calling thread first, which unblocks a worker parked in
     * `line.write`, and the thread is then joined only briefly: this runs on the Compose dispatcher,
     * so a long block here would be a visible UI stall, and a worker that outlives that grace period
     * still exits on its own because [released] is already set.
     */
    fun release() {
        released = true
        playing = false
        seekRequestMs = null
        line?.let { current ->
            runCatching { current.stop() }
            runCatching { current.flush() }
            runCatching { current.close() }
        }
        line = null
        worker?.let { runCatching { it.join(400) } }
        worker = null
    }

    // ------------------------------------------------------------------ worker

    /**
     * Decodes [file] and feeds the device until the stream ends, a seek is requested, or playback is
     * released.
     *
     * Failures are classified where they happen: anything wrong with opening or writing the line is
     * a device error, anything wrong with reading or converting the source is a decode error. That
     * split is what lets the UI say "no output device" instead of "unsupported file" when a machine
     * simply has no working output. A line closed by [release] raises one of those exceptions too,
     * which is why every report is guarded by `!released`.
     */
    private fun run(file: File) {
        var startMs = position.get()
        try {
            while (!released) {
                val source = try {
                    openPcmStream(file)
                } catch (t: Throwable) {
                    throw DecodeFailure(t)
                }
                source.use { stream ->
                    // Which format the device really accepts is only known by trying to open it, so
                    // the plans are walked best-first: the source's own rate and depth, then 16-bit
                    // fallbacks, then the usual consumer rates.
                    val choice = try {
                        openOutput(stream.format)
                    } catch (t: Throwable) {
                        throw DeviceFailure(t)
                    }
                    val pcm = choice.format
                    val converter = try {
                        PcmConverter(stream.format, pcm, System.nanoTime())
                    } catch (t: Throwable) {
                        // The line is already open and the caller is about to see a failure, so close
                        // it here rather than leave the device busy.
                        runCatching { choice.line.close() }
                        throw DecodeFailure(t)
                    }
                    val rate = pcm.frameRate.toLong().coerceAtLeast(1L)
                    val frameSize = pcm.frameSize.coerceAtLeast(1)
                    // The duration comes from the file itself: once the converter is resampling, the
                    // stream's frame length no longer describes the chosen output rate.
                    val totalMs = if (stream.frameLength > 0) {
                        stream.frameLength * 1000L /
                            stream.format.frameRate.toLong().coerceAtLeast(1L)
                    } else {
                        0L
                    }
                    val device = choice.line
                    line = device
                    try {
                        duration.set(totalMs)
                        // Seeking happens in the source domain: the converter is still at its first
                        // frame, so no resampler history has to be rewound.
                        try {
                            skipTo(
                                stream,
                                startMs,
                                stream.format.frameRate.toLong().coerceAtLeast(1L),
                                stream.format.frameSize.coerceAtLeast(1),
                            )
                        } catch (t: Throwable) {
                            throw DecodeFailure(t)
                        }
                        framesRead.set(0L)
                        position.set(startMs)
                        listener.onStarted(totalMs)

                        var ended = false
                        try {
                            if (playing) device.start() else device.stop()
                        } catch (t: Throwable) {
                            throw DeviceFailure(t)
                        }
                        val buffer = ByteArray(BUFFER_BYTES - (BUFFER_BYTES % frameSize))
                        loop@ while (!released) {
                            val seek = seekRequestMs
                            if (seek != null) {
                                seekRequestMs = null
                                startMs = seek.coerceAtLeast(0L)
                                position.set(startMs)
                                break@loop
                            }
                            if (!playing) {
                                try {
                                    if (device.isRunning) device.stop()
                                } catch (t: Throwable) {
                                    throw DeviceFailure(t)
                                }
                                Thread.sleep(20L)
                                continue
                            }
                            try {
                                if (!device.isRunning) device.start()
                            } catch (t: Throwable) {
                                throw DeviceFailure(t)
                            }
                            val read = try {
                                converter.read(stream, buffer)
                            } catch (t: Throwable) {
                                throw DecodeFailure(t)
                            }
                            if (read <= 0) {
                                ended = true
                                break@loop
                            }
                            var offset = 0
                            while (offset < read && !released && playing) {
                                val written = try {
                                    device.write(buffer, offset, read - offset)
                                } catch (t: Throwable) {
                                    throw DeviceFailure(t)
                                }
                                offset += written
                            }
                            val writtenFrames = (offset / frameSize).toLong()
                            if (writtenFrames > 0L) {
                                position.set(
                                    startMs + framesRead.addAndGet(writtenFrames) * 1000L / rate,
                                )
                            }
                        }
                        if (ended && !released) {
                            // Let the device buffer drain so the last fraction of a second is
                            // audible instead of being cut off by close().
                            runCatching { device.drain() }
                            listener.onEnded()
                            return
                        }
                    } finally {
                        runCatching { device.stop() }
                        runCatching { device.flush() }
                        runCatching { device.close() }
                        line = null
                    }
                }
            }
        } catch (failure: DecodeFailure) {
            if (!released) listener.onFailure(PlaybackErrorCode.DECODE_ERROR)
        } catch (failure: DeviceFailure) {
            if (!released) listener.onFailure(PlaybackErrorCode.DEVICE_ERROR)
        } catch (t: Throwable) {
            // A closed line surfaces here as an IllegalStateException; that is the normal path out
            // of release(), so it must not be reported as a failure.
            if (!released) listener.onFailure(PlaybackErrorCode.DECODE_ERROR)
        }
    }

    /**
     * Skips [startMs] into [stream] by reading and discarding, which every reader supports.
     *
     * The skip is expressed in source frames, not output frames: the stream has not been converted
     * yet, and letting the converter start at a byte offset would corrupt the 24-bit sample grid and
     * the resampler history.
     */
    private fun skipTo(stream: AudioInputStream, startMs: Long, rate: Long, frameSize: Int) {
        if (startMs <= 0L) return
        var remaining = startMs * rate / 1000L * frameSize
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L && !released) {
            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            remaining -= read
        }
    }

    /**
     * Opens the output line, retrying briefly.
     *
     * The previous track's line may still be closing when a skip happens, and Windows keeps the
     * device busy until it is fully released; a short retry turns an audible "device unavailable"
     * failure into a barely noticeable delay.
     */
    private fun openLine(pcm: AudioFormat): SourceDataLine {
        val info = DataLine.Info(SourceDataLine::class.java, pcm)
        var lastFailure: Throwable? = null
        repeat(4) { attempt ->
            try {
                val device = AudioSystem.getLine(info) as SourceDataLine
                device.open(pcm, (pcm.frameRate * pcm.frameSize * 0.5f).toInt().coerceAtLeast(4096))
                applyGain(device)
                return device
            } catch (t: Throwable) {
                lastFailure = t
                if (attempt < 3) Thread.sleep(120L)
            }
        }
        throw lastFailure ?: IllegalStateException("no audio device")
    }

    private fun applyGain(device: SourceDataLine) {
        runCatching {
            val gain = device.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val decibels = if (volume <= 0.0001f) {
                gain.minimum
            } else {
                (20.0 * log10(volume.toDouble())).toFloat()
            }
            gain.value = decibels.coerceIn(gain.minimum, gain.maximum)
        }
    }

    /** One way of feeding the source to the device: the format it is converted to, and the open line. */
    private data class OutputChoice(
        val format: AudioFormat,
        val line: SourceDataLine,
    )

    /**
     * Opens the best output the machine will actually accept for [source].
     *
     * Java Sound's `isLineSupported` is optimistic — it answers `true` for rates far above anything
     * the hardware plays, and 1-bit DSD decoded to 176.4 kHz PCM is refused outright by mixers that
     * only speak 48 kHz. So every plan from [outputPlans] is opened for real and the first that works
     * wins; only when they all fail is the last failure reported.
     */
    private fun openOutput(source: AudioFormat): OutputChoice {
        var lastFailure: Throwable? = null
        for (plan in outputPlans(source)) {
            val format = outputFormatOf(plan)
            try {
                return OutputChoice(format, openLine(format))
            } catch (t: Throwable) {
                lastFailure = t
            }
        }
        throw lastFailure ?: IllegalStateException("no audio device")
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

/**
 * Rates tried when the device refuses the file's own rate. 48 kHz first because it is what every
 * Windows mixer has supported for two decades; 44.1 kHz is the fallback for a device that only
 * speaks CD audio.
 */
internal val DEVICE_RATES = floatArrayOf(48_000f, 44_100f)

/**
 * Nothing above this is ever handed to the device. Windows mixers report support well beyond what
 * they render (the probe answered `true` even for 384 kHz), and a rate the hardware silently
 * mishandles is worse than one Java Sound converts for us.
 */
internal const val MAX_DEVICE_RATE_HZ = 192_000f

/**
 * The output formats to try for [base], best first.
 *
 * The file's own format comes first when the device can plausibly take it, which is the path every
 * ordinary track has always taken. Anything above [MAX_DEVICE_RATE_HZ] is deliberately never offered:
 * that is the "192 kHz and up fails" symptom, because a mixer claims to support 384 kHz but does not
 * actually play it at that rate.
 */
internal fun outputFormatsFor(base: AudioFormat): List<AudioFormat> {
    val result = ArrayList<AudioFormat>(DEVICE_RATES.size + 1)
    if (base.frameRate <= MAX_DEVICE_RATE_HZ) result += base
    for (rate in DEVICE_RATES) {
        if (rate == base.frameRate) continue
        result += rateFormat(base, rate)
    }
    return result
}

/** A copy of [base] at [rate] as 16-bit signed little-endian PCM. */
internal fun rateFormat(base: AudioFormat, rate: Float): AudioFormat = AudioFormat(
    AudioFormat.Encoding.PCM_SIGNED,
    rate,
    16,
    base.channels.coerceAtLeast(1),
    base.channels.coerceAtLeast(1) * 2,
    rate,
    false,
)

/**
 * Opens [file] as **signed PCM**, which is the only thing [PcmConverter] can work with.
 *
 * `AudioSystem.getAudioInputStream(file)` does *not* decode: it hands back the stream the file
 * actually is — `MPEG1L3` for an MP3, `FLAC` for a FLAC — and a compressed frame has no fixed sample
 * size, so those formats report `sampleSizeInBits = -1` and `frameSize`/`frameRate` describe
 * compressed frames rather than samples. Decoding is done by the SPI's format-conversion provider, so
 * the raw stream has to be re-wrapped with `getAudioInputStream(pcm, raw)`.
 *
 * That detail used to hide inside the old `convert()` helper; when the PCM pipeline replaced it, the
 * engine handed the still-compressed stream to the converter, whose `require(source.sampleSizeInBits
 * in 8..64)` threw, and **every** track — NetEase or local, MP3 or FLAC — surfaced as "decode error".
 *
 * Several PCM layouts are tried because providers differ in what they can hand back: the file's own
 * depth when it is a real one, then 16-bit, at the file's own rate, then the two consumer rates. Each
 * attempt re-opens the raw stream, since a failed conversion may already have consumed it.
 */
internal fun openPcmStream(file: File): AudioInputStream {
    val raw = AudioSystem.getAudioInputStream(file)
    val source = raw.format
    if (isPcmEncoding(source.encoding)) return raw
    raw.close()

    val channels = if (source.channels in 1..8) source.channels else 2
    val rate = source.sampleRate.toDouble().takeIf { it in 8_000.0..MAX_DEVICE_RATE_HZ.toDouble() }
    val depths = LinkedHashSet<Int>()
    val ownBits = source.sampleSizeInBits
    if (ownBits in 8..24 && ownBits % 8 == 0) depths += ownBits
    depths += 16
    val rates = ArrayList<Float>(3)
    if (rate != null) rates += rate.toFloat()
    for (fallback in DEVICE_RATES) if (rates.none { abs(it - fallback) < 0.5f }) rates += fallback

    var lastFailure: Throwable? = null
    for (bits in depths) {
        for (tryRate in rates) {
            val target = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                tryRate,
                bits,
                channels,
                channels * (bits / 8),
                tryRate,
                false,
            )
            val attempt = try {
                AudioSystem.getAudioInputStream(file)
            } catch (t: Throwable) {
                lastFailure = t
                continue
            }
            val converted = try {
                AudioSystem.getAudioInputStream(target, attempt)
            } catch (t: Throwable) {
                lastFailure = t
                runCatching { attempt.close() }
                continue
            }
            // The converted stream may be a thin wrapper that keeps reading the raw stream lazily
            // (FLAC is), so it is deliberately *not* closed here: closing the wrapper closes it.
            return converted
        }
    }

    // Some providers (JAAD's AAC, for one) only accept the exact target format they advertise, which
    // can carry an unknown sample rate and a big-endian layout. Ask them instead of guessing, then
    // relabel the result so the pipeline sees a rate it can plan around.
    val providerTargets = ArrayList<AudioFormat>()
    for (provider in ServiceLoader.load(FormatConversionProvider::class.java)) {
        val targets = runCatching { provider.getTargetFormats(AudioFormat.Encoding.PCM_SIGNED, source) }
            .getOrNull()
            ?: continue
        for (target in targets) {
            if (target.encoding == AudioFormat.Encoding.PCM_SIGNED) providerTargets += target
        }
    }
    for (target in providerTargets) {
        val attempt = try {
            AudioSystem.getAudioInputStream(file)
        } catch (t: Throwable) {
            lastFailure = t
            continue
        }
        val converted = try {
            AudioSystem.getAudioInputStream(target, attempt)
        } catch (t: Throwable) {
            lastFailure = t
            runCatching { attempt.close() }
            continue
        }
        return relabelUnknown(converted, source)
    }
    throw lastFailure ?: IllegalStateException("no PCM decoder for ${file.name}")
}

/**
 * Fills in a sample rate or channel count the provider left as "unknown" (`-1`).
 *
 * The bytes are already what we asked for, so this only re-labels the stream rather than routing it
 * through another converter -- the guessed format would be rejected by the same provider.
 */
private fun relabelUnknown(stream: AudioInputStream, source: AudioFormat): AudioInputStream {
    val format = stream.format
    val rate = if (format.sampleRate > 0f) format.sampleRate else source.sampleRate
    val channels = if (format.channels > 0) format.channels else source.channels
    if (format.sampleRate > 0f && format.channels > 0) return stream
    val fixed = AudioFormat(
        format.encoding,
        rate,
        format.sampleSizeInBits,
        channels,
        channels * (format.sampleSizeInBits / 8),
        rate,
        format.isBigEndian,
    )
    return AudioInputStream(stream, fixed, stream.frameLength)
}

/** True for the three PCM encodings [PcmConverter] can consume without a provider's help. */
private fun isPcmEncoding(encoding: AudioFormat.Encoding): Boolean =
    encoding == AudioFormat.Encoding.PCM_SIGNED ||
        encoding == AudioFormat.Encoding.PCM_UNSIGNED ||
        encoding == AudioFormat.Encoding.PCM_FLOAT

/** Forces 16-bit signed little-endian PCM, the one format every output device accepts. */
internal fun pcmFormatFor(base: AudioFormat): AudioFormat {
    val isPcm16 = base.encoding == AudioFormat.Encoding.PCM_SIGNED &&
        base.sampleSizeInBits == 16 &&
        !base.isBigEndian
    if (isPcm16) return base
    return AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        base.sampleRate,
        16,
        base.channels.coerceAtLeast(1),
        base.channels.coerceAtLeast(1) * 2,
        base.sampleRate,
        false,
    )
}
