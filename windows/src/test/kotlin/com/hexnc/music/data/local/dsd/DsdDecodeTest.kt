package com.hexnc.music.data.local.dsd

import com.hexnc.music.data.local.isDsdSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * End-to-end tests for the DSD path: a synthesized DSF/DFF container is parsed, the payload is
 * decoded, and the resulting PCM is checked for the signal that went in.
 *
 * The fixtures are built here rather than checked in as binaries: the bitstream is produced by a
 * first-order sigma-delta modulator, which is exactly what a real DSD file contains, so the tests
 * verify the math instead of merely matching a recorded file.
 */
class DsdDecodeTest {

    // ------------------------------------------------------------------ container parsing

    @Test
    fun `a dsf header describes its stream`() {
        val data = dsfBytes(channels = 2, bitRate = DSD64, planes = listOf(ByteArray(4_096), ByteArray(4_096)))
        val info = DsdProbe.parse(ByteArrayInputStream(data))
        assertNotNull(info)
        assertEquals(DsdContainer.DSF, info!!.container)
        assertEquals(2, info.channels)
        assertEquals(DSD64, info.bitRateHz)
        assertEquals(4_096L * 8, info.bitsPerChannel)
        assertEquals(4_096, info.blockSizePerChannel)
        assertTrue("DSF stores the least significant bit first", info.lsbFirst)
        assertEquals(4_096L * 8 / 32, info.outputFrames(32))
    }

    @Test
    fun `a dff header describes its stream`() {
        val data = dffBytes(channels = 2, bitRate = DSD64, payload = ByteArray(2 * 512))
        val info = DsdProbe.parse(ByteArrayInputStream(data))
        assertNotNull(info)
        assertEquals(DsdContainer.DFF, info!!.container)
        assertEquals(2, info.channels)
        assertEquals(DSD64, info.bitRateHz)
        assertEquals(512L * 8, info.bitsPerChannel)
        assertFalse("DFF stores the most significant bit first", info.lsbFirst)
    }

    @Test
    fun `dst compression is reported as undecodable`() {
        val compressed = dffBytes(channels = 2, bitRate = DSD64, payload = ByteArray(2 * 512), compress = true)
        assertNull(DsdProbe.parse(ByteArrayInputStream(compressed)))
    }

    @Test
    fun `a file that is not dsd at all is rejected`() {
        assertNull(DsdProbe.parse(ByteArrayInputStream("ID3\u0004not dsd at all".toByteArray())))
        assertNull(DsdProbe.parse(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `dsd source detection is extension based`() {
        assertTrue(isDsdSource("/music/album/track.dsf"))
        assertTrue(isDsdSource("C:\\Music\\Album\\track.DFF"))
        assertTrue(isDsdSource("content://com.android.providers/track.dsd"))
        assertFalse(isDsdSource("/music/album/track.flac"))
        assertFalse(isDsdSource("/music/album.dsf/track"))
        assertFalse(isDsdSource(null))
        assertFalse(isDsdSource("  "))
    }

    // ------------------------------------------------------------------ decoding

    @Test
    fun `dsd64 decodes to the frame count in the header`() {
        val frames = 4_096 * 8 / 32
        val data = dsfBytes(channels = 1, bitRate = DSD64, planes = listOf(ByteArray(4_096)))
        val stream = ByteArrayInputStream(data)
        val info = DsdProbe.parse(stream)!!
        val pcm = DsdPcmInputStream(info, stream, TARGET_RATE)
        val decoded = readAll(pcm)
        // 16-bit mono: two bytes per frame. The decoder cannot emit the stream's final half-window
        // (there are no samples after it to filter against), so it comes out a hair short.
        assertTrue("expected about ${frames * 2} bytes, got ${decoded.size}", decoded.size in (frames * 2 - 64)..(frames * 2))
    }

    @Test
    fun `a one kilohertz tone survives the decimation`() {
        val bitRate = DSD64
        val seconds = 0.4
        val samples = DoubleArray((bitRate * seconds).toInt()) { index ->
            0.5 * Math.sin(2.0 * Math.PI * 1_000.0 * index / bitRate)
        }
        val data = dsfBytes(channels = 1, bitRate = bitRate, planes = listOf(sigmaDelta(samples)))
        val stream = ByteArrayInputStream(data)
        val info = DsdProbe.parse(stream)!!
        val pcm = DsdPcmInputStream(info, stream, TARGET_RATE)

        val decoded = readSamples(pcm)
        // 0.4 s at 88 200 Hz, minus the filter's start-up transient and the tail of the last block.
        assertTrue("expected about ${(TARGET_RATE * seconds).toInt()} frames, got ${decoded.size}", decoded.size > 30_000)

        val window = decoded.drop(2_000)
        val rms = Math.sqrt(window.sumOf { it.toDouble() * it.toDouble() } / window.size)
        // A 0.5-amplitude sine has an RMS of 0.3536 full scale.
        assertTrue("RMS $rms is not close to the encoded tone", rms > 0.26 * 32_767 && rms < 0.45 * 32_767)

        var crossings = 0
        for (index in 1 until window.size) {
            if (window[index - 1] < 0 && window[index] >= 0) crossings++
        }
        val secondsAnalysed = window.size.toDouble() / TARGET_RATE
        val expected = 1_000.0 * secondsAnalysed
        assertTrue("$crossings rising crossings, expected about ${expected.toInt()}", Math.abs(crossings - expected) < expected * 0.2)
    }

    @Test
    fun `the two channels of a stereo file stay separate`() {
        val bitRate = DSD64
        val frames = bitRate / 4
        val tone = DoubleArray(frames) { 0.5 * Math.sin(2.0 * Math.PI * 440.0 * it / bitRate) }
        val silence = DoubleArray(frames)
        val data = dsfBytes(
            channels = 2,
            bitRate = bitRate,
            planes = listOf(sigmaDelta(tone), sigmaDelta(silence)),
        )
        val stream = ByteArrayInputStream(data)
        val info = DsdProbe.parse(stream)!!
        val pcm = DsdPcmInputStream(info, stream, TARGET_RATE)
        val interleaved = readSamples(pcm)

        val left = interleaved.filterIndexed { index, _ -> index % 2 == 0 }.drop(2_000)
        val right = interleaved.filterIndexed { index, _ -> index % 2 == 1 }.drop(2_000)

        fun rms(values: List<Short>): Double =
            Math.sqrt(values.sumOf { it.toDouble() * it.toDouble() } / values.size)

        assertTrue("left channel is silent: ${rms(left)}", rms(left) > 0.2 * 32_767)
        assertTrue("right channel is not silent: ${rms(right)}", rms(right) < 0.02 * 32_767)
    }

    @Test
    fun `dff decodes most significant bit first`() {
        val bitRate = DSD64
        val samples = DoubleArray(bitRate / 4) { 0.5 * Math.sin(2.0 * Math.PI * 1_000.0 * it / bitRate) }
        val data = dffBytes(channels = 1, bitRate = bitRate, payload = sigmaDeltaMsb(samples))
        val stream = ByteArrayInputStream(data)
        val info = DsdProbe.parse(stream)!!
        assertFalse(info.lsbFirst)
        val decoded = readSamples(DsdPcmInputStream(info, stream, TARGET_RATE)).drop(2_000)
        val rms = Math.sqrt(decoded.sumOf { it.toDouble() * it.toDouble() } / decoded.size)
        assertTrue("RMS $rms is not close to the encoded tone", rms > 0.26 * 32_767 && rms < 0.45 * 32_767)
    }

    // ------------------------------------------------------------------ filter maths

    @Test
    fun `decimation factors are powers of two at or above the target`() {
        assertEquals(32, decimationFactor(DSD64, 88_200))
        assertEquals(16, decimationFactor(DSD64, 176_400))
        assertEquals(64, decimationFactor(DSD128, 88_200))
        assertEquals(1, decimationFactor(DSD64, DSD64))
        assertEquals(1, decimationFactor(44_100, 44_100))
    }

    @Test
    fun `half band taps are symmetric and sum to one`() {
        val taps = halfBandTaps(15)
        assertEquals(15, taps.size)
        assertEquals(1f, taps.sum(), 1e-4f)
        for (index in taps.indices) {
            assertEquals("tap $index", taps[index], taps[taps.size - 1 - index], 1e-6f)
        }
        // Every even offset from the centre vanishes, which is what defines a half-band filter. The
        // offsets either side of the centre (±1) are legitimately nonzero.
        assertEquals(0f, taps[7 - 2], 1e-6f)
        assertEquals(0f, taps[7 + 2], 1e-6f)
        assertTrue("centre neighbours should carry the signal", Math.abs(taps[7 - 1]) > 0.1f)
        assertTrue("centre neighbours should carry the signal", Math.abs(taps[7 + 1]) > 0.1f)
    }

    // ------------------------------------------------------------------ fixtures

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

    private fun readSamples(stream: InputStream): List<Short> {
        val bytes = readAll(stream)
        return List(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xFF) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
        }
    }

    /** First-order sigma-delta modulation: the bit density of the result follows [samples]. */
    private fun sigmaDelta(samples: DoubleArray): ByteArray = modulate(samples, lsbFirst = true)

    private fun sigmaDeltaMsb(samples: DoubleArray): ByteArray = modulate(samples, lsbFirst = false)

    private fun modulate(samples: DoubleArray, lsbFirst: Boolean): ByteArray {
        val out = ByteArray((samples.size + 7) / 8)
        var integrator = 0.0
        for (index in samples.indices) {
            val quantised = if (integrator >= 0.0) 1.0 else -1.0
            if (quantised > 0.0) {
                val bit = if (lsbFirst) index % 8 else 7 - index % 8
                out[index / 8] = (out[index / 8].toInt() or (1 shl bit)).toByte()
            }
            integrator += samples[index] - quantised
        }
        return out
    }

    private fun dsfBytes(channels: Int, bitRate: Int, planes: List<ByteArray>, blockSize: Int = 4_096): ByteArray {
        require(planes.size == channels)
        val bytesPerChannel = maxOf(planes.maxOf { it.size }, blockSize)
        val blocks = (bytesPerChannel + blockSize - 1) / blockSize
        val payload = ByteArrayOutputStream()
        for (block in 0 until blocks) {
            for (channel in 0 until channels) {
                val plan = planes[channel]
                val from = block * blockSize
                val length = (plan.size - from).coerceIn(0, blockSize)
                val piece = ByteArray(blockSize)
                if (length > 0) System.arraycopy(plan, from, piece, 0, length)
                payload.write(piece)
            }
        }
        val body = payload.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(ascii("DSD "))
        out.write(le64(28))
        out.write(le64((28 + 52 + 12 + body.size).toLong()))
        out.write(le64(0))
        out.write(ascii("fmt "))
        out.write(le64(52))
        out.write(le32(1))
        out.write(le32(0))
        out.write(le32(2))
        out.write(le32(channels))
        out.write(le32(bitRate))
        out.write(le32(1))
        out.write(le64(planes[0].size.toLong() * 8))
        out.write(le32(blockSize))
        out.write(le32(0))
        out.write(ascii("data"))
        out.write(le64((12 + body.size).toLong()))
        out.write(body)
        return out.toByteArray()
    }

    private fun dffBytes(
        channels: Int,
        bitRate: Int,
        payload: ByteArray,
        compress: Boolean = false,
    ): ByteArray {
        val properties = ByteArrayOutputStream()
        properties.write(ascii("FS  "))
        properties.write(be64(4))
        properties.write(be32(bitRate))
        properties.write(ascii("CHNL"))
        properties.write(be64(2))
        properties.write(be16(channels))
        val props = properties.toByteArray()

        val chunks = ByteArrayOutputStream()
        chunks.write(ascii("FVER"))
        chunks.write(be64(4))
        chunks.write(be32(0x01050000))
        chunks.write(ascii("PROP"))
        chunks.write(be64((4 + props.size).toLong()))
        chunks.write(ascii("SND "))
        chunks.write(props)
        chunks.write(ascii(if (compress) "DST " else "DSD "))
        chunks.write(be64(payload.size.toLong()))
        chunks.write(payload)

        val body = chunks.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(ascii("FRM8"))
        out.write(be64((4 + body.size).toLong()))
        out.write(ascii("DSD "))
        out.write(body)
        return out.toByteArray()
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun le64(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 32) and 0xFF).toByte(),
        ((value shr 40) and 0xFF).toByte(),
        ((value shr 48) and 0xFF).toByte(),
        ((value shr 56) and 0xFF).toByte(),
    )

    private fun be16(value: Int): ByteArray = byteArrayOf(
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun be32(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun be64(value: Long): ByteArray = byteArrayOf(
        ((value shr 56) and 0xFF).toByte(),
        ((value shr 48) and 0xFF).toByte(),
        ((value shr 40) and 0xFF).toByte(),
        ((value shr 32) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private companion object {
        const val DSD64 = 2_822_400
        const val DSD128 = 5_644_800
        const val TARGET_RATE = 88_200
    }
}
