package com.hexnc.music.playback

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

/**
 * The desktop playback engine: Java Sound, decoded to 16-bit PCM and written to a `SourceDataLine`.
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

    private fun run(file: File) {
        var startMs = position.get()
        try {
            while (!released) {
                AudioSystem.getAudioInputStream(file).use { source ->
                    // Which format the device really accepts is only known by trying to open it, so
                    // the candidates are walked in order of preference: the file's own rate (or the
                    // DSD decoder's PCM) first, then the usual consumer rates.
                    val choice = openOutput(source)
                    val stream = choice.stream
                    val pcm = choice.format
                    try {
                        val rate = pcm.frameRate.toLong().coerceAtLeast(1L)
                        val frameSize = pcm.frameSize.coerceAtLeast(1)
                        // The duration comes from the file itself, not from the chosen output: a
                        // converted stream's frame length is only reliable for the rate it was asked
                        // for, and the device may have made us fall back to another one.
                        val totalMs = if (source.frameLength > 0) {
                            source.frameLength * 1000L / source.format.frameRate.toLong().coerceAtLeast(1L)
                        } else {
                            0L
                        }
                        duration.set(totalMs)
                        skipTo(stream, startMs, rate, frameSize)
                        framesRead.set(0L)
                        position.set(startMs)
                        listener.onStarted(totalMs)

                        val device = choice.line
                        line = device
                        var ended = false
                        try {
                            if (playing) device.start() else device.stop()
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
                                    if (device.isRunning) device.stop()
                                    Thread.sleep(20L)
                                    continue
                                }
                                if (!device.isRunning) device.start()
                                val read = stream.read(buffer, 0, buffer.size)
                                if (read <= 0) {
                                    ended = true
                                    break@loop
                                }
                                var offset = 0
                                while (offset < read && !released && playing) {
                                    offset += device.write(buffer, offset, read - offset)
                                }
                                val writtenFrames = (offset / frameSize).toLong()
                                if (writtenFrames > 0L) {
                                    position.set(startMs + framesRead.addAndGet(writtenFrames) * 1000L / rate)
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
                    } finally {
                        if (stream !== source) runCatching { stream.close() }
                    }
                }
            }
        } catch (t: Throwable) {
            // A closed line surfaces here as an IllegalStateException; that is the normal path out
            // of release(), so it must not be reported as a decoding failure.
            if (!released) listener.onFailure(PlaybackErrorCode.DECODING_FAILED)
        }
    }

    /** Skips [startMs] into [stream] by reading and discarding, which every reader supports. */
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

    /** One way of feeding a source to the device: the stream to read, its format, and the open line. */
    private data class OutputChoice(
        val stream: AudioInputStream,
        val format: AudioFormat,
        val line: SourceDataLine,
    )

    /**
     * Opens the best output the machine will actually accept for [source].
     *
     * Java Sound's `isLineSupported` is optimistic — it answers `true` for rates far above anything
     * the hardware plays, and 1-bit DSD decoded to 176.4 kHz PCM is refused outright by mixers that
     * only speak 48 kHz. So every candidate from [outputCandidates] is opened for real and the first
     * that works wins; only when they all fail is the last failure reported.
     */
    private fun openOutput(source: AudioInputStream): OutputChoice {
        var lastFailure: Throwable? = null
        for ((stream, format) in outputCandidates(source)) {
            try {
                return OutputChoice(stream, format, openLine(format))
            } catch (t: Throwable) {
                lastFailure = t
            }
        }
        throw lastFailure ?: IllegalStateException("no audio device")
    }

    /**
     * The stream/format pairs to try, best first.
     *
     * The file's own PCM format comes first, which is the path every ordinary track has always taken.
     * Anything above [MAX_DEVICE_RATE_HZ] is deliberately never offered to the device: a mixer claims
     * to support 384 kHz but does not actually play it at that rate, which is exactly the "high
     * sample rate file fails" symptom, so those are converted down instead. The conversion uses Java
     * Sound's own rate converter, and a target rate with no converter is simply skipped.
     */
    private fun outputCandidates(source: AudioInputStream): List<Pair<AudioInputStream, AudioFormat>> {
        val basePcm = pcmFormatFor(source.format)
        val baseStream = if (basePcm == source.format) source else convert(basePcm, source) ?: return emptyList()
        val result = ArrayList<Pair<AudioInputStream, AudioFormat>>(DEVICE_RATES.size + 1)
        for (format in outputFormatsFor(basePcm)) {
            if (format == basePcm) {
                result += baseStream to basePcm
                continue
            }
            val converted = convert(format, baseStream) ?: continue
            result += converted to format
        }
        return result
    }

    /** Java Sound's conversion, or null when no installed provider supports this pair. */
    private fun convert(format: AudioFormat, source: AudioInputStream): AudioInputStream? =
        runCatching { AudioSystem.getAudioInputStream(format, source) }.getOrNull()

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
