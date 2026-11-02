package com.blek.blexsy

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class BlexsyMediaService : MediaSessionService() {
    companion object {
        var sharedPlayer: AndroidBlexsyAudioPlayer? = null
            set(value) {
                field = value
                instance?.syncSession()
            }
        private var instance: BlexsyMediaService? = null

        fun start(context: Context) {
            try {
                val intent = Intent(context, BlexsyMediaService::class.java)
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private var fallbackPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("blexsy_playback")
                .setChannelName(R.string.app_name)
                .build()
        )
        syncSession()
    }

    internal fun syncSession() {
        val currentAudioPlayer = sharedPlayer
        if (currentAudioPlayer != null) {
            fallbackPlayer?.release()
            fallbackPlayer = null
            val oldSession = mediaSession
            val newSession = currentAudioPlayer.mediaSession
            if (oldSession != null && oldSession != newSession) {
                runCatching {
                    if (sessions.contains(oldSession)) {
                        removeSession(oldSession)
                    }
                }
            }
            if (!sessions.contains(newSession)) {
                addSession(newSession)
            }
            mediaSession = newSession
            return
        }

        if (mediaSession == null) {
            val player = ExoPlayer.Builder(this)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
            fallbackPlayer = player

            val sessionActivity = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val session = MediaSession.Builder(this, player)
                .setId("blexsy-media-service")
                .setSessionActivity(sessionActivity)
                .build()
            mediaSession = session
            addSession(session)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncSession()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        sharedPlayer?.mediaSession ?: mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val prefs = getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
        val keepPlayingClosed = prefs.getBoolean("pref_keep_playing_closed", true)
        val player = (sharedPlayer?.mediaSession ?: mediaSession)?.player
        if (!keepPlayingClosed || player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        fallbackPlayer?.release()
        fallbackPlayer = null
        if (sharedPlayer == null) {
            mediaSession?.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
