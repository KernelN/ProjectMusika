package com.musika.player.core.sync

import com.musika.player.core.model.AudioQuality
import com.musika.player.core.model.LoopMode
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stage 2 Desktop Sync Contract.
 * Implements Local-Match-First resolution for cross-device desktop and mobile synchronization.
 */
object RemoteSyncContract {
    const val PROTOCOL_VERSION = "2.0.0"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class RemoteTrackDescriptor(
        // Primary local-match identifiers
        val relativePathFromRoot: String,
        val normalizedTrackKey: String, // artist|album|title
        val durationMs: Long,
        // Informational metadata
        val title: String,
        val artist: String,
        val album: String,
        val trackNumber: Int = 1,
        val discNumber: Int = 1,
        val audioQuality: AudioQuality? = null,
        // Fallback for streaming if track is not present locally on desktop/device
        val lanStreamUri: String? = null
    ) {
        /**
         * Checks if a local track matches this remote descriptor.
         * Match priority:
         * 1. Exact relativePathFromRoot
         * 2. normalizedTrackKey + duration within 2000ms tolerance
         */
        fun matchesLocal(localRelativePath: String, localNormalizedKey: String, localDurationMs: Long): Boolean {
            if (this.relativePathFromRoot.isNotEmpty() &&
                this.relativePathFromRoot.equals(localRelativePath, ignoreCase = true)
            ) {
                return true
            }
            if (this.normalizedTrackKey.isNotEmpty() &&
                this.normalizedTrackKey.equals(localNormalizedKey, ignoreCase = true)
            ) {
                return kotlin.math.abs(this.durationMs - localDurationMs) <= 2000
            }
            return false
        }

        companion object {
            fun fromTrack(track: Track, lanHost: String? = null): RemoteTrackDescriptor {
                val streamUri = if (!lanHost.isNullOrBlank()) {
                    "http://$lanHost/stream?path=${java.net.URLEncoder.encode(track.relativePath, "UTF-8")}"
                } else {
                    "lan://stream/${track.relativePath}"
                }
                return RemoteTrackDescriptor(
                    relativePathFromRoot = track.relativePath,
                    normalizedTrackKey = track.normalizedKey,
                    durationMs = track.durationMs,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    trackNumber = track.trackNumber,
                    discNumber = track.discNumber,
                    audioQuality = track.audioQuality,
                    lanStreamUri = streamUri
                )
            }
        }
    }

    @Serializable
    data class PlaybackSnapshot(
        val version: String = PROTOCOL_VERSION,
        val timestamp: Long = System.currentTimeMillis(),
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val currentTrack: RemoteTrackDescriptor? = null,
        val randomMode: RandomMode = RandomMode.OFF,
        val loopMode: LoopMode = LoopMode.NO_LOOP,
        // Dual queues:
        // Immediate/Priority Queue (FIFO "Play Next")
        val priorityQueue: List<RemoteTrackDescriptor> = emptyList(),
        // Standard Context Queue ("Add at End" / active playlist)
        val standardQueue: List<RemoteTrackDescriptor> = emptyList(),
        val activeContextName: String? = null
    )

    @Serializable
    sealed class SyncCommand {
        @Serializable
        data object Play : SyncCommand()

        @Serializable
        data object Pause : SyncCommand()

        @Serializable
        data object TogglePlayPause : SyncCommand()

        @Serializable
        data class SeekTo(val positionMs: Long) : SyncCommand()

        @Serializable
        data object SkipNext : SyncCommand()

        @Serializable
        data object SkipPrevious : SyncCommand()

        @Serializable
        data class SetRandom(val mode: RandomMode) : SyncCommand()

        @Serializable
        data class SetLoop(val mode: LoopMode) : SyncCommand()

        @Serializable
        data class Enqueue(
            val track: RemoteTrackDescriptor,
            val immediate: Boolean // true: Play Next (FIFO priority), false: Add at End
        ) : SyncCommand()

        @Serializable
        data class PlayNow(
            val track: RemoteTrackDescriptor,
            val contextTracks: List<RemoteTrackDescriptor>
        ) : SyncCommand()

        @Serializable
        data class StateUpdate(val snapshot: PlaybackSnapshot) : SyncCommand()
    }

    fun serializeSnapshot(snapshot: PlaybackSnapshot): String = json.encodeToString(snapshot)
    fun deserializeSnapshot(payload: String): PlaybackSnapshot = json.decodeFromString(payload)

    fun serializeCommand(command: SyncCommand): String = json.encodeToString(command)
    fun deserializeCommand(payload: String): SyncCommand = json.decodeFromString(payload)
}
