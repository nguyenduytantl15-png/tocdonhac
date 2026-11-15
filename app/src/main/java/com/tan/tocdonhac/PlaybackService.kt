package com.tan.tocdonhac

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            checkState()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, true)       // tự dừng khi có cuộc gọi / app khác phát
            .setHandleAudioBecomingNoisy(true)     // tự dừng khi rút tai nghe
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    Shared.abA = -1L; Shared.abB = -1L
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    Shared.sleepEndOfTrack = false
                    player.pauseAtEndOfMediaItems = false
                }
            }
        })

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(pi)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> {
                    val list = mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                        if (uri != null) item.buildUpon().setUri(uri).build() else item
                    }.toMutableList()
                    return Futures.immediateFuture(list)
                }
            })
            .build()

        handler.post(tick)
    }

    private fun checkState() {
        // Lặp đoạn A-B
        val a = Shared.abA
        val b = Shared.abB
        if (a >= 0 && b > a && player.currentPosition >= b) player.seekTo(a)

        // Hẹn giờ: tắt khi hết bài
        if (player.pauseAtEndOfMediaItems != Shared.sleepEndOfTrack) {
            player.pauseAtEndOfMediaItems = Shared.sleepEndOfTrack
        }

        // Hẹn giờ theo phút (có giảm dần âm lượng)
        val end = Shared.sleepEndAt
        if (end > 0) {
            val remain = end - SystemClock.elapsedRealtime()
            when {
                remain <= 0 -> {
                    player.pause()
                    player.volume = 1f
                    Shared.sleepEndAt = 0L
                }
                Shared.fadeOut && remain < Shared.FADE_MS ->
                    player.volume = (remain.toFloat() / Shared.FADE_MS).coerceIn(0f, 1f)
                player.volume < 1f -> player.volume = 1f
            }
        } else if (player.volume < 1f) {
            player.volume = 1f
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
