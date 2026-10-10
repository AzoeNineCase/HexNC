package com.hexnc.music.playback

import com.hexnc.music.data.local.dsd.DsdPcmInputStream
import com.hexnc.music.data.local.dsd.DsdProbe
import com.hexnc.music.data.local.dsd.DsdTrackInfo
import com.hexnc.music.data.local.dsd.decimationFactor
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.UnsupportedAudioFileException
import javax.sound.sampled.spi.AudioFileReader

/**
 * Teaches Java Sound to open DSD files (`.dsf` / `.dff`).
 *
 * Java Sound has no DSD support of any kind: `AudioSystem.getAudioInputStream` on a `.dsf` file
 * answers `UnsupportedAudioFileException`. Rather than special-case the transport, this service
 * provider plugs the decoder into the platform, so [AudioEngine] keeps calling
 * `AudioSystem.getAudioInputStream(file)` and simply receives decoded 16-bit PCM.
 *
 * The provider is registered in `META-INF/services/javax.sound.sampled.spi.AudioFileReader`, which
 * is what makes the JDK's `ServiceLoader` find it. It only claims streams whose first four bytes are
 * a DSD magic, so every other format keeps falling through to the readers that already handle it.
 *
 * Output format: 16-bit signed little-endian PCM at [TARGET_RATE_HZ]. That rate is deliberately above
 * 44.1/48 kHz because the DSD noise floor only clears the audio band once it has been filtered well
 * above it; the transport's own sample-rate conversion handles the step down to whatever the machine
 * actually plays.
 */
class DsdAudioFileReader : AudioFileReader() {

    override fun getAudioFileFormat(stream: InputStream): AudioFileFormat {
        val buffered = buffered(stream)
        buffered.mark(PROBE_BYTES)
        val info = try {
            DsdProbe.parse(buffered)
        } catch (io: IOException) {
            null
        } finally {
            buffered.reset()
        }
        return formatOf(info ?: throw UnsupportedAudioFileException("Not a DSD stream"))
    }

    override fun getAudioInputStream(stream: InputStream): AudioInputStream {
        val buffered = buffered(stream)
        // Peek without consuming: the JDK walks every provider in turn, and a stream we have eaten
        // four bytes from would look corrupt to the reader that actually understands it.
        buffered.mark(PROBE_BYTES)
        val info = try {
            DsdProbe.parse(buffered)
        } catch (io: IOException) {
            null
        }
        if (info == null) {
            buffered.reset()
            throw UnsupportedAudioFileException("Not a DSD stream")
        }
        return pcmStream(info, buffered)
    }

    override fun getAudioFileFormat(url: URL): AudioFileFormat =
        BufferedInputStream(url.openStream()).use { getAudioFileFormat(it) }

    override fun getAudioInputStream(url: URL): AudioInputStream {
        val stream = BufferedInputStream(url.openStream())
        return try {
            getAudioInputStream(stream)
        } catch (t: Throwable) {
            stream.close()
            throw t
        }
    }

    override fun getAudioFileFormat(file: File): AudioFileFormat =
        BufferedInputStream(FileInputStream(file)).use { getAudioFileFormat(it) }

    override fun getAudioInputStream(file: File): AudioInputStream {
        val stream = BufferedInputStream(FileInputStream(file))
        return try {
            getAudioInputStream(stream)
        } catch (t: Throwable) {
            stream.close()
            throw t
        }
    }

    private fun pcmStream(info: DsdTrackInfo, source: InputStream): AudioInputStream {
        val decoded = DsdPcmInputStream(info, source, TARGET_RATE_HZ)
        return AudioInputStream(decoded, formatOf(info).format, decoded.frameLength)
    }

    private fun formatOf(info: DsdTrackInfo): AudioFileFormat = AudioFileFormat(
        // There is no DSD file type in Java Sound, and PCM is what the caller actually receives.
        AudioFileFormat.Type.WAVE,
        AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            TARGET_RATE_HZ.toFloat(),
            16,
            info.channels,
            info.channels * 2,
            TARGET_RATE_HZ.toFloat(),
            false,
        ),
        // AudioFileFormat counts frames as an Int; an hour of DSD64 stereo stays well inside it.
        info.outputFrames(decimationFactor(info.bitRateHz, TARGET_RATE_HZ))
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt(),
    )

    private fun buffered(stream: InputStream): BufferedInputStream =
        if (stream is BufferedInputStream && stream.markSupported()) stream else BufferedInputStream(stream)

    private companion object {
        /** Enough for the longest header (a DFF with several property chunks) and then some. */
        const val PROBE_BYTES = 64 * 1024

        /** PCM rate the decoder produces; the transport converts down as the device requires. */
        const val TARGET_RATE_HZ = 176_400
    }
}
