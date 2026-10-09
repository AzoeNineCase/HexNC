package com.hexnc.music.playback

import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Playing audio whose sample rate is above what `AudioTrack` accepts on the device.
 *
 * A FLAC or WAV file at 192 kHz and above (and the occasional 352.8/384 kHz master) reaches
 * `AudioTrack` at its native rate, and `AudioTrack` answers with an initialisation failure —
 * ExoPlayer surfaces that as `ERROR_CODE_AUDIO_TRACK_INIT_FAILED`, which is indistinguishable from a
 * broken file. The default ExoPlayer pipeline never changes the sample rate: its `SonicAudioProcessor`
 * is configured with `SAMPLE_RATE_NO_CHANGE` and is only there for speed/pitch.
 *
 * The fix is a resampler in the audio sink that engages *only* when the stream is faster than the
 * device's own output rate and is otherwise a straight pass-through, so ordinary 44.1/48 kHz files
 * keep the exact code path they had before. It is installed through [HexRenderersFactory] instead of
 * enabling float output, because `DefaultAudioSink` puts its own `ToInt16PcmAudioProcessor` at the
 * front of the pipeline and a user `AudioProcessor` after it — which means the resampler always sees
 * 16-bit PCM and never has to deal with 24-bit or float input.
 */

/** Lowest rate `AudioTrack` is willing to open. */
internal const val MIN_AUDIO_TRACK_SAMPLE_RATE = 8_000

/** Highest rate `AudioTrack` is documented to accept, on any Android version. */
internal const val MAX_AUDIO_TRACK_SAMPLE_RATE = 192_000

/** What the device reports when it reports nothing — the overwhelmingly common output rate. */
internal const val FALLBACK_SAMPLE_RATE = 48_000

/**
 * The rate [inputRateHz] should be resampled to, or `null` when it can be left alone.
 *
 * Pure on purpose: the decision is the part worth testing, and it is deliberately conservative —
 * a device that already runs at the stream's rate (or faster) is never touched, so this cannot
 * introduce a resample where the platform handled the stream fine before.
 */
internal fun resampleTargetHz(inputRateHz: Int, deviceRateHz: Int): Int? {
    if (inputRateHz <= 0) return null
    val target = if (deviceRateHz > 0) {
        deviceRateHz.coerceIn(MIN_AUDIO_TRACK_SAMPLE_RATE, MAX_AUDIO_TRACK_SAMPLE_RATE)
    } else {
        FALLBACK_SAMPLE_RATE
    }
    return if (inputRateHz > target) target else null
}

/**
 * The sample rate the device's mixer runs at, which is what `AudioTrack` opens most reliably.
 *
 * `AudioManager.getProperty` is the documented source but is allowed to return null, and
 * `AudioTrack.getNativeOutputSampleRate` needs no permission, so both are tried before falling back
 * to 48 kHz. The value is clamped because a bogus property must not become a bogus target rate.
 */
internal fun deviceOutputSampleRateHz(context: Context): Int {
    val fromProperty = runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.trim()
            ?.toIntOrNull()
    }.getOrNull()
    val rate = fromProperty ?: runCatching {
        AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
    }.getOrDefault(FALLBACK_SAMPLE_RATE)
    return if (rate > 0) {
        rate.coerceIn(MIN_AUDIO_TRACK_SAMPLE_RATE, MAX_AUDIO_TRACK_SAMPLE_RATE)
    } else {
        FALLBACK_SAMPLE_RATE
    }
}

/**
 * A resampler that does nothing unless the stream is faster than the device can play.
 *
 * When it does engage it is a plain delegation to [SonicAudioProcessor] — the same battle-tested
 * resampler ExoPlayer uses for playback speed — configured once with the device rate. When it does
 * not, [isActive] returns false and `DefaultAudioSink` leaves it out of the pipeline entirely, so the
 * pass-through path costs nothing at runtime and nothing in fidelity.
 */
internal class AdaptiveResampleProcessor(private val deviceRateHz: Int) : AudioProcessor {

    private val sonic = SonicAudioProcessor()

    private var active = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val target = resampleTargetHz(inputAudioFormat.sampleRate, deviceRateHz)
        if (target == null) {
            active = false
            return inputAudioFormat
        }
        return try {
            sonic.setOutputSampleRateHz(target)
            val output = sonic.configure(inputAudioFormat)
            active = true
            output
        } catch (_: AudioProcessor.UnhandledAudioFormatException) {
            // Sonic resamples 16-bit PCM only. The sink converts to 16-bit before the pipeline's
            // custom processors, so this should be unreachable — but if it ever is reached, passing
            // the stream through untouched is strictly better than failing the whole track.
            active = false
            sonic.reset()
            inputAudioFormat
        }
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (active) sonic.queueInput(inputBuffer)
    }

    override fun queueEndOfStream() {
        if (active) sonic.queueEndOfStream()
    }

    override fun getOutput(): ByteBuffer = if (active) sonic.getOutput() else AudioProcessor.EMPTY_BUFFER

    override fun isEnded(): Boolean = active && sonic.isEnded()

    // Only the metadata overload is implemented, exactly like SonicAudioProcessor itself: the sink
    // calls this one, and the interface's no-argument default deliberately throws so that a
    // processor cannot silently ignore a flush.
    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        sonic.flush(streamMetadata)
    }

    override fun reset() {
        active = false
        sonic.reset()
    }
}

/**
 * The one renderer factory the app uses, so the resampling sink is in place for every track.
 *
 * Overriding `buildAudioSink` (rather than replacing the whole renderers list) keeps every other
 * renderer — container, metadata, text, and the platform audio decoders — exactly as ExoPlayer
 * configured them, and keeps the factory's own float-output decision intact.
 */
internal class HexRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessors(arrayOf(AdaptiveResampleProcessor(deviceOutputSampleRateHz(context))))
        .setEnableFloatOutput(enableFloatOutput)
        // The renamed form of `setEnableAudioTrackPlaybackParams`, which media3 deprecated; the flag
        // still means "let AudioTrack apply speed/pitch changes instead of the Sonic processor".
        .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
        .build()
}
