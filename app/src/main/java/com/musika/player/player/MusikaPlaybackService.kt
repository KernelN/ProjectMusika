package com.musika.player.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.musika.player.R
import com.musika.player.core.model.Track
import com.musika.player.ui.MainActivity
import java.io.File

/**
 * Media3 Foreground MediaSessionService + Real ExoPlayer Bridge powering uninterrupted
 * background audio playback (WakeLock + Foreground Service), Android system Media Style
 * notification (Previous, Play/Pause, Next, interactive Song Time Seekbar, Cover Art),
 * real-time seekbar progress advancement, and automatic track transitions.
 */
@OptIn(UnstableApi::class)
class MusikaPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val notificationProvider = DefaultMediaNotificationProvider.Builder(applicationContext)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_music_notification)
            }
        setMediaNotificationProvider(notificationProvider)

        val player = getOrCreateSharedPlayer(applicationContext)
        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands()
                    .buildUpon()
                    .add(COMMAND_PLAY_PAUSE)
                    .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
                    .add(COMMAND_GET_TIMELINE)
                    .add(COMMAND_GET_METADATA)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                if (command == COMMAND_PLAY_PAUSE ||
                    command == COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM ||
                    command == COMMAND_SEEK_TO_NEXT ||
                    command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == COMMAND_SEEK_TO_PREVIOUS ||
                    command == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                ) {
                    return true
                }
                return super.isCommandAvailable(command)
            }

            override fun play() {
                super.play()
                attachedQueueEngine?.setPlaying(true)
            }

            override fun pause() {
                super.pause()
                attachedQueueEngine?.setPlaying(false)
            }

            override fun seekTo(positionMs: Long) {
                super.seekTo(positionMs)
                attachedQueueEngine?.updateProgressFromPlayer(positionMs, duration.coerceAtLeast(0L))
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                super.seekTo(mediaItemIndex, positionMs)
                attachedQueueEngine?.updateProgressFromPlayer(positionMs, duration.coerceAtLeast(0L))
            }

            override fun seekToNext() {
                attachedQueueEngine?.skipToNext(autoTriggered = false)
            }

            override fun seekToNextMediaItem() {
                attachedQueueEngine?.skipToNext(autoTriggered = false)
            }

            override fun seekToPrevious() {
                attachedQueueEngine?.skipToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                attachedQueueEngine?.skipToPrevious()
            }
        }

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val sessionActivityPendingIntent = PendingIntent.getActivity(this, 0, activityIntent, pendingFlags)

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
        activeServiceInstance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        activeServiceInstance = null
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        var activeServiceInstance: MusikaPlaybackService? = null
            private set

        @Volatile
        private var sharedExoPlayer: ExoPlayer? = null

        @Volatile
        private var controllerFuture: ListenableFuture<MediaController>? = null

        @Volatile
        private var attachedQueueEngine: DualQueueEngine? = null

        private val mainHandler = Handler(Looper.getMainLooper())
        private var progressTickerRunning = false

        private val progressRunnable = object : Runnable {
            override fun run() {
                val player = sharedExoPlayer
                val engine = attachedQueueEngine
                if (player != null && engine != null) {
                    try {
                        if (player.isPlaying || player.playWhenReady) {
                            val pos = player.currentPosition.coerceAtLeast(0L)
                            val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                            engine.updateProgressFromPlayer(pos, dur)
                        }
                    } catch (_: Exception) {
                    }
                }
                if (progressTickerRunning) {
                    mainHandler.postDelayed(this, 200L)
                }
            }
        }

        @Synchronized
        fun getOrCreateSharedPlayer(context: Context): ExoPlayer {
            sharedExoPlayer?.let { return it }
            val appCtx = context.applicationContext
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            val player = ExoPlayer.Builder(appCtx)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                        val pos = player.currentPosition.coerceAtLeast(0L)
                        attachedQueueEngine?.updateProgressFromPlayer(pos, dur)
                    } else if (playbackState == Player.STATE_ENDED) {
                        attachedQueueEngine?.skipToNext(autoTriggered = true)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    attachedQueueEngine?.updateProgressFromPlayer(pos, dur)
                }
            })

            sharedExoPlayer = player
            return player
        }

        private fun ensureMediaControllerConnected(context: Context) {
            if (controllerFuture != null) return
            try {
                val appCtx = context.applicationContext
                val sessionToken = SessionToken(appCtx, ComponentName(appCtx, MusikaPlaybackService::class.java))
                controllerFuture = MediaController.Builder(appCtx, sessionToken).buildAsync()
            } catch (_: Exception) {
            }
        }

        /**
         * Connects [dualQueueEngine] to the real Android [ExoPlayer] + [MediaSessionService]
         * and starts the 200ms progress ticker.
         */
        fun attachEngineToExoPlayer(context: Context, dualQueueEngine: DualQueueEngine) {
            val appCtx = context.applicationContext
            attachedQueueEngine = dualQueueEngine
            runOnMainThread {
                ensureServiceStarted(appCtx)
                ensureMediaControllerConnected(appCtx)
            }

            dualQueueEngine.playbackDriver = object : DualQueueEngine.PlaybackDriver {
                override fun onLoadAndPlayTrack(track: Track, startPositionMs: Long, playWhenReady: Boolean) {
                    runOnMainThread {
                        try {
                            ensureServiceStarted(appCtx)
                            ensureMediaControllerConnected(appCtx)
                            val player = getOrCreateSharedPlayer(appCtx)
                            val mediaItem = buildMediaItem(track)
                            player.setMediaItem(mediaItem)
                            player.prepare()
                            if (startPositionMs > 0L) {
                                player.seekTo(startPositionMs)
                            }
                            player.playWhenReady = playWhenReady
                            if (playWhenReady) {
                                player.play()
                            }
                            startProgressTicker()
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun onPlayStateChanged(isPlaying: Boolean) {
                    runOnMainThread {
                        try {
                            ensureServiceStarted(appCtx)
                            ensureMediaControllerConnected(appCtx)
                            val player = getOrCreateSharedPlayer(appCtx)
                            if (isPlaying && player.mediaItemCount == 0) {
                                val currentTrack = dualQueueEngine.state.value.currentTrack
                                if (currentTrack != null) {
                                    player.setMediaItem(buildMediaItem(currentTrack))
                                    player.prepare()
                                }
                            } else if (isPlaying && player.playbackState == Player.STATE_IDLE) {
                                player.prepare()
                            } else if (isPlaying && player.playbackState == Player.STATE_ENDED) {
                                player.seekTo(0L)
                            }
                            player.playWhenReady = isPlaying
                            if (isPlaying) {
                                player.play()
                                startProgressTicker()
                            } else {
                                player.pause()
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun onSeekTo(positionMs: Long) {
                    runOnMainThread {
                        try {
                            val player = getOrCreateSharedPlayer(appCtx)
                            player.seekTo(positionMs.coerceAtLeast(0L))
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        private fun startProgressTicker() {
            if (!progressTickerRunning) {
                progressTickerRunning = true
                mainHandler.post(progressRunnable)
            }
        }

        private fun ensureServiceStarted(context: Context) {
            if (activeServiceInstance == null) {
                try {
                    val intent = Intent(context, MusikaPlaybackService::class.java)
                    ContextCompat.startForegroundService(context, intent)
                } catch (_: Exception) {
                    try {
                        context.startService(Intent(context, MusikaPlaybackService::class.java))
                    } catch (_: Exception) {
                    }
                }
            }
        }

        private fun runOnMainThread(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                block()
            } else {
                mainHandler.post(block)
            }
        }

        fun buildMediaItem(track: Track): MediaItem {
            val uri = if (track.filePath.startsWith("http://") ||
                track.filePath.startsWith("https://") ||
                track.filePath.startsWith("lan://")
            ) {
                Uri.parse(track.filePath)
            } else {
                Uri.fromFile(File(track.filePath))
            }

            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setDiscNumber(track.discNumber)
                .setTrackNumber(track.trackNumber)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

            if (!track.artworkUri.isNullOrBlank()) {
                try {
                    metadataBuilder.setArtworkUri(Uri.parse(track.artworkUri))
                } catch (_: Exception) {
                }
            }

            return MediaItem.Builder()
                .setMediaId(track.filePath)
                .setUri(uri)
                .setMediaMetadata(metadataBuilder.build())
                .build()
        }
    }
}
