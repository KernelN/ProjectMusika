package com.musika.player.core.sync

import com.musika.player.core.model.AudioQuality
import com.musika.player.core.model.LoopMode
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stage 2 Online Control & Multi-Device Sync Contract.
 * Supports:
 * 1. Controlling the active playing device remotely ([SyncCommand]).
 * 2. Choosing whether to keep devices of the same account synchronized ([AccountDeviceSyncMode.SYNCED_PLAYBACK])
 *    or use 2+ different devices of the same account independently at the same time ([AccountDeviceSyncMode.INDEPENDENT_CONCURRENT]).
 * 3. Moving/transferring music playback from one device to another, strictly gated by verifying
 *    that both devices have a song with the same metadata loaded ([canTransferTrackToDevice] / [SyncCommand.TransferPlayback]).
 */
object RemoteSyncContract {
    const val PROTOCOL_VERSION = "2.1.0"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Multi-device account synchronization mode:
     * - [SYNCED_PLAYBACK]: Keep devices on the same account synchronized in real time.
     * - [INDEPENDENT_CONCURRENT]: Allow 2+ devices on the same account to play different music simultaneously.
     */
    @Serializable
    enum class AccountDeviceSyncMode(val displayName: String, val subtitle: String) {
        SYNCED_PLAYBACK(
            displayName = "Keep Devices Synced",
            subtitle = "Mirrors playback state across active devices on this account"
        ),
        INDEPENDENT_CONCURRENT(
            displayName = "Independent Simultaneous Playback",
            subtitle = "Use 2+ devices on the same account independently at the same time"
        )
    }

    /**
     * Represents a connected device on the user's Musika account (Desktop or Mobile).
     */
    @Serializable
    data class ConnectedDeviceNode(
        val deviceId: String,
        val deviceName: String,
        val deviceType: String = "ANDROID", // ANDROID, DESKTOP, WEB
        val accountId: String = "musika-default-account",
        val isThisDevice: Boolean = false,
        val isActivePlayingDevice: Boolean = false,
        val lastSnapshot: PlaybackSnapshot? = null,
        val loadedTrackKeys: Set<String> = emptySet(),
        val loadedRelativePaths: Set<String> = emptySet()
    ) {
        /**
         * Returns true if this target device has a track loaded whose metadata matches [trackDescriptor].
         * Per user specification: moving music from one device to another can ONLY be done if both
         * devices have a song with the same metadata loaded!
         */
        fun hasMatchingTrackMetadataLoaded(trackDescriptor: RemoteTrackDescriptor): Boolean {
            val normKey = trackDescriptor.normalizedTrackKey.trim().lowercase()
            if (normKey.isNotEmpty() && loadedTrackKeys.any { it.equals(normKey, ignoreCase = true) }) {
                return true
            }
            val relPath = trackDescriptor.relativePathFromRoot.trim()
            if (relPath.isNotEmpty() && loadedRelativePaths.any { it.equals(relPath, ignoreCase = true) }) {
                return true
            }
            val snap = lastSnapshot ?: return false
            val allLoadedOnDevice = buildList {
                snap.currentTrack?.let { add(it) }
                addAll(snap.priorityQueue)
                addAll(snap.standardQueue)
            }
            return allLoadedOnDevice.any { candidate ->
                trackDescriptor.matchesLocal(
                    localRelativePath = candidate.relativePathFromRoot,
                    localNormalizedKey = candidate.normalizedTrackKey,
                    localDurationMs = candidate.durationMs
                )
            }
        }
    }

    /**
     * Result of attempting to transfer active music playback from one device to another.
     */
    @Serializable
    data class PlaybackTransferResult(
        val allowed: Boolean,
        val sourceDeviceId: String,
        val targetDeviceId: String,
        val transferredTrack: RemoteTrackDescriptor? = null,
        val reason: String
    )

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

        @Serializable
        data class SetAccountSyncMode(val mode: AccountDeviceSyncMode) : SyncCommand()

        @Serializable
        data class TransferPlayback(
            val sourceDeviceId: String,
            val targetDeviceId: String,
            val snapshot: PlaybackSnapshot,
            val pauseSourceDevice: Boolean = true
        ) : SyncCommand()
    }

    /**
     * Verifies whether active playback on [sourceSnapshot] can be moved to [targetDevice]
     * (requires both devices to have a track with the same metadata loaded) and builds the result.
     */
    fun validateAndPreparePlaybackTransfer(
        sourceDeviceId: String,
        sourceSnapshot: PlaybackSnapshot,
        targetDevice: ConnectedDeviceNode
    ): PlaybackTransferResult {
        val activeTrack = sourceSnapshot.currentTrack
            ?: return PlaybackTransferResult(
                allowed = false,
                sourceDeviceId = sourceDeviceId,
                targetDeviceId = targetDevice.deviceId,
                transferredTrack = null,
                reason = "No active track is currently loaded on source device."
            )

        if (!targetDevice.hasMatchingTrackMetadataLoaded(activeTrack)) {
            return PlaybackTransferResult(
                allowed = false,
                sourceDeviceId = sourceDeviceId,
                targetDeviceId = targetDevice.deviceId,
                transferredTrack = activeTrack,
                reason = "Cannot move playback: target device '${targetDevice.deviceName}' does not have '${activeTrack.title}' (${activeTrack.artist} — ${activeTrack.album}) with matching metadata loaded."
            )
        }

        return PlaybackTransferResult(
            allowed = true,
            sourceDeviceId = sourceDeviceId,
            targetDeviceId = targetDevice.deviceId,
            transferredTrack = activeTrack,
            reason = "Metadata verified on '${targetDevice.deviceName}'. Transferred '${activeTrack.title}' at ${sourceSnapshot.positionMs}ms."
        )
    }

    fun serializeSnapshot(snapshot: PlaybackSnapshot): String = json.encodeToString(snapshot)
    fun deserializeSnapshot(payload: String): PlaybackSnapshot = json.decodeFromString(payload)

    fun serializeCommand(command: SyncCommand): String = json.encodeToString(command)
    fun deserializeCommand(payload: String): SyncCommand = json.decodeFromString(payload)
}
