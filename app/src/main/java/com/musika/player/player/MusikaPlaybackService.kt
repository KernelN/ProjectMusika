package com.musika.player.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.musika.player.core.model.Track
import java.io.File

/**
 * Media3 Foreground MediaSessionService powering background audio playback
 * and hardware media button controls.
 */
class MusikaPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer = player
        mediaSession = MediaSession.Builder(this, player).build()
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
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var activeServiceInstance: MusikaPlaybackService? = null
            private set

        fun buildMediaItem(track: Track): MediaItem {
            val uri = if (track.filePath.startsWith("http://") ||
                track.filePath.startsWith("https://") ||
                track.filePath.startsWith("lan://")
            ) {
                android.net.Uri.parse(track.filePath)
            } else {
                android.net.Uri.fromFile(File(track.filePath))
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setDiscNumber(track.discNumber)
                .setTrackNumber(track.trackNumber)
                .build()

            return MediaItem.Builder()
                .setMediaId(track.filePath)
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()
        }
    }
}
