package com.hexnc.music.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.hexnc.music.MainActivity

/**
 * Hosts playback in the foreground.
 *
 * The service owns the one `ExoPlayer` in the process and publishes it through a `MediaSession`,
 * which is what makes playback survive the UI being backgrounded and puts transport controls on the
 * lock screen, in the notification shade, on Bluetooth headsets and in Android Auto. The UI never
 * touches the player directly — it talks to a `MediaController` bound to this session (see
 * [PlaybackController]), so the two halves can live in different processes if that ever becomes
 * necessary.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            // Route audio through the resampling sink so that a 192 kHz+ stream is brought down to the
            // device's own output rate instead of being rejected by AudioTrack (see AudioResampling.kt).
            .setRenderersFactory(HexRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // Handle the audio focus ourselves so that playback ducks and pauses the way a music
                // app is expected to when a call or another player takes over.
                true,
            )
            // Pause rather than keep blasting when headphones are unplugged.
            .setHandleAudioBecomingNoisy(true)
            // Keep decoding while the screen is off and the network is needed for a stream.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent())
            .build()
    }

    /** Tapping the notification returns to the now-playing screen rather than a fresh launch. */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Called when the user swipes the app away. Playback is allowed to continue, but an idle
     * session is shut down so that the process is not kept alive for nothing.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player: Player? = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
