// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.tux4us.miriquidilauncher.MainActivity
import io.github.tux4us.miriquidilauncher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Wiedergabedienst der Musik-Scope (Media3) für lokale Lieder und Radiostreams. Er läuft als Vordergrunddienst vom Typ
 * `mediaPlayback` mit Benachrichtigung und Mediensitzung (Sperrbildschirm, Kopfhörertasten, Bluetooth), solange etwas
 * geladen ist, und hat einen eigenen Player samt Benachrichtigung neben dem Podcast-Dienst. Der Ton läuft durch den
 * [EqualizerAudioProcessor]. HTTP-Streams sind nur bei eingeschaltetem Schalter erlaubt ([MusicRuntime.allowHttp]).
 */
@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null
    private val processor = EqualizerAudioProcessor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        MusicRuntime.attach(processor)
        // Einstellungen aus dem Speicher, falls der Dienst vor der Oberfläche startet (z. B. Kopfhörertaste).
        scope.launch {
            val data = MusicStore(this@MusicService).load()
            MusicRuntime.setAllowHttp(data.allowHttp)
            MusicRuntime.setEq(data.eq)
        }

        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
        }
        // Der Schalter wird bei jeder Verbindung gelesen: Weiterleitungen von HTTPS auf HTTP sind nur mit Erlaubnis möglich.
        val http = DataSource.Factory {
            DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(MusicRuntime.allowHttp)
                .createDataSource()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Eigene Sitzungskennung und Benachrichtigung, damit der Podcast-Dienst (Standardwerte) nicht überschrieben wird.
        session = MediaSession.Builder(this, player).setId(SESSION_ID).setSessionActivity(open).build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.music_notification_channel)
                .build(),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Wird die App aus der Übersicht entfernt, endet der Dienst, wenn nichts abgespielt wird.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        MusicRuntime.detach(processor)
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val SESSION_ID = "music"
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "music_playback"
        const val USER_AGENT = "MiriquidiLauncher/1.0 (FOSS Android launcher; https://github.com/tux4us)"
    }
}
