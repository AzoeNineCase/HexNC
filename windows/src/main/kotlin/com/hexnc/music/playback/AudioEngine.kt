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
                    val basePcm = pcmFormatFor(source.format)
                    val baseStream =
                        if (basePcm == source.format) source else AudioSystem.getAudioInputStream(basePcm, source)
                    // A 192 kHz file (or any rate this machine's mixer refuses) would otherwise fail
                    // the whole track. Asking Java Sound for a rate the device does accept is what
                    // lets those files play; when no converter is available the original format is
                    // kept, which leaves the previous behaviour — including the failure report —
                    // exactly as it was.
                    val preferred = deviceRateFormat(basePcm)
                    var chosenPcm = basePcm
                    var stream = baseStream
                    if (preferred != basePcm) {
                        val converted = runCatching { AudioSystem.getAudioInputStream(preferred, baseStream) }
                            .getOrNull()
                        if (converted != null) {
                            stream = converted
                            chosenPcm = preferred
                        }
                    }
                    val pcm = chosenPcm
                    try {
                        val rate = pcm.frameRate.toLong().coerceAtLeast(1L)
                        val frameSize = pcm.frameSize.coerceAtLeast(1)
                        val totalFrames = if (stream.frameLength > 0) stream.frameLength else source.frameLength
                        val totalMs = if (totalFrames > 0) totalFrames * 1000L / rate else 0L
                        duration.set(totalMs)
                        skipTo(stream, startMs, rate, frameSize)
                        framesRead.set(0L)
                        position.set(startMs)
                        listener.onStarted(totalMs)

                        val device = openLine(pcm)
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

    /**
     * Picks a PCM rate the output device will actually open.
     *
     * [base] is returned untouched whenever the device accepts it, so ordinary files take exactly the
     * path they always did. Only when the mixer refuses the format — a 192 kHz local file is the
     * common way to hit that — is the first widely supported consumer rate chosen instead, and Java
     * Sound then converts the samples on the way to the line.
     */
    private fun deviceRateFormat(base: AudioFormat): AudioFormat {
        fun supported(format: AudioFormat): Boolean = runCatching {
            AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format))
        }.getOrDefault(false)

        if (supported(base)) return base
        for (rate in DEVICE_RATES) {
            val candidate = AudioFormat(
                base.encoding,
                rate,
                base.sampleSizeInBits,
                base.channels,
                base.channels * (base.sampleSizeInBits / 8),
                rate,
                base.isBigEndian,
            )
            if (supported(candidate)) return candidate
        }
        return base
    }

    /** Forces 16-bit signed little-endian PCM, the one format every output device accepts. */
    private fun pcmFormatFor(base: AudioFormat): AudioFormat {
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

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        /**
         * Rates tried when the device refuses the file's own rate. 48 kHz first because it is what
         * every Windows mixer has supported for two decades; 44.1 kHz is the fallback for a device
         * that only speaks CD audio.
         */
        val DEVICE_RATES = floatArrayOf(48_000f, 44_100f)
    }
}
