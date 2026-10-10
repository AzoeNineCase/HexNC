package com.hexnc.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * End-to-end checks for the Java Sound DSD provider.
 *
 * The decoder's own tests cover the maths; these cover the wiring that is easy to get subtly wrong:
 * the `META-INF/services` registration (without it `ServiceLoader` never finds the provider, and
 * `AudioSystem.getAudioInputStream` keeps answering `UnsupportedAudioFileException`), and the
 * peek-without-consuming behaviour that keeps every other format falling through to the readers that
 * already handle it.
 */
class DsdServiceProviderTest {

    @Test
    fun `audio system decodes a dsf file through the service provider`() {
        val bitRate = 2_822_400
        val seconds = 0.25
        val samples = DoubleArray((bitRate * seconds).toInt()) { index ->
            0.5 * Math.sin(2.0 * Math.PI * 1_000.0 * index / bitRate)
        }
        val file = writeTemp(dsfMono(bitRate, sigmaDelta(samples)), ".dsf")
        try {
            val info = AudioSystem.getAudioFileFormat(file)
            assertEquals(AudioFileFormat.Type.WAVE, info.type)
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, info.format.encoding)
            assertEquals(176_400f, info.format.sampleRate, 1e-3f)
            assertEquals(16, info.format.sampleSizeInBits)
            assertEquals(1, info.format.channels)

            AudioSystem.getAudioInputStream(file).use { stream ->
                assertEquals(176_400f, stream.format.sampleRate, 1e-3f)
                val decoded = readAll(stream)
                assertTrue("decoded ${decoded.size} bytes", decoded.size > 176_400 * seconds * 2 * 0.8)
                val rms = rms(decoded)
                assertTrue("RMS $rms is not close to the encoded tone", rms > 0.2 * 32_767)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a non dsd file still opens through the jdk readers`() {
        val format = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44_100f, 16, 1, 2, 44_100f, false)
        val frames = 4_410L
        val file = writeTemp(ByteArray((frames * 2).toInt()), ".wav")
        try {
            AudioInputStream(ByteArrayInputStream(ByteArray((frames * 2).toInt())), format, frames).use { audio ->
                AudioSystem.write(audio, AudioFileFormat.Type.WAVE, file)
            }
            assertEquals(AudioFileFormat.Type.WAVE, AudioSystem.getAudioFileFormat(file).type)
            AudioSystem.getAudioInputStream(file).use { stream ->
                assertEquals(AudioFormat.Encoding.PCM_SIGNED, stream.format.encoding)
                assertEquals(44_100f, stream.format.sampleRate, 1e-3f)
                assertEquals(16, stream.format.sampleSizeInBits)
            }
        } finally {
            file.delete()
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun writeTemp(bytes: ByteArray, suffix: String): File {
        val file = File.createTempFile("hexnc-dsd-test-", suffix)
        file.writeBytes(bytes)
        return file
    }

    private fun readAll(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun rms(bytes: ByteArray): Double {
        val values = List(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xFF) or (bytes[index * 2 + 1].toInt() shl 8)).toShort().toDouble()
        }.drop(2_000)
        if (values.isEmpty()) return 0.0
        return Math.sqrt(values.sumOf { it * it } / values.size)
    }

    /** First-order sigma-delta modulation: the bit density of the result follows [samples]. */
    private fun sigmaDelta(samples: DoubleArray): ByteArray {
        val out = ByteArray((samples.size + 7) / 8)
        var integrator = 0.0
        for (index in samples.indices) {
            val quantised = if (integrator >= 0.0) 1.0 else -1.0
            if (quantised > 0.0) {
                out[index / 8] = (out[index / 8].toInt() or (1 shl (index % 8))).toByte()
            }
            integrator += samples[index] - quantised
        }
        return out
    }

    /** A minimal mono DSF container: `DSD ` header, one `fmt ` chunk (1-bit LSB-first), one `data` chunk. */
    private fun dsfMono(bitRate: Int, payload: ByteArray, blockSize: Int = 4_096): ByteArray {
        val blocks = (payload.size + blockSize - 1) / blockSize
        val body = ByteArray(blocks * blockSize)
        System.arraycopy(payload, 0, body, 0, payload.size)

        val out = ByteArrayOutputStream()
        out.write(ascii("DSD "))
        out.write(le64(28))
        out.write(le64(28L + 52 + 12 + body.size))
        out.write(le64(0)) // no metadata pointer
        out.write(ascii("fmt "))
        out.write(le64(52))
        out.write(le32(1)) // format version
        out.write(le32(0)) // format id: DSD raw
        out.write(le32(1)) // channel type: mono
        out.write(le32(1)) // channels
        out.write(le32(bitRate)) // DSD bit rate
        out.write(le32(1)) // 1 bit per sample, least significant bit first
        out.write(le64(payload.size.toLong() * 8))
        out.write(le32(blockSize))
        out.write(le32(0))
        out.write(ascii("data"))
        out.write(le64(12L + body.size))
        out.write(body)
        return out.toByteArray()
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun le64(value: Long): ByteArray = ByteArray(8) { index -> ((value shr (index * 8)) and 0xFF).toByte() }
}
