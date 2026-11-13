package com.musika.player.core.sync

import com.musika.player.core.model.AudioQuality
import com.musika.player.core.model.LoopMode
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.Track
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
    const val PROTOCOL_VERSION = "2.2.0"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    enum class ConnectionTransportMode(val label: String) {
        LAN_DIRECT("LAN Direct (Wi-Fi)"),
        ONLINE_SUPABASE("Online Relay (Supabase)"),
        OFFLINE_LOCAL("Offline Local Only")
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
        val loadedRelativePaths: Set<String> = emptySet(),
        val lanHost: String? = null,
        val lanPort: Int? = null,
        val catalogTracks: List<RemoteLibraryTrackSummary> = emptyList()
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
            if (catalogTracks.any { summary ->
                    trackDescriptor.matchesLocal(
                        localRelativePath = summary.relativePath,
                        localNormalizedKey = summary.normalizedKey,
                        localDurationMs = summary.durationMs
                    )
                }
            ) {
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
     *
     * Respects Q5 hybrid rule:
     * - The active track MUST match target metadata locally (never bypassed).
     * - Follow-up queue tracks missing on [targetDevice] are skipped by default (`allowLanStreamingFallback == false`),
     *   or kept with `lanStreamUri` only when the user explicitly turns ON LAN streaming on Wi-Fi.
     */
    fun validateAndPreparePlaybackTransfer(
        sourceDeviceId: String,
        sourceSnapshot: PlaybackSnapshot,
        targetDevice: ConnectedDeviceNode,
        allowLanStreamingFallback: Boolean = false
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

        val skippedCount = if (allowLanStreamingFallback) {
            0
        } else {
            (sourceSnapshot.priorityQueue + sourceSnapshot.standardQueue).count { qTrack ->
                !targetDevice.hasMatchingTrackMetadataLoaded(qTrack)
            }
        }
        val skippedSuffix = if (skippedCount > 0) {
            " ($skippedCount queued item(s) missing locally skipped)"
        } else {
            ""
        }

        return PlaybackTransferResult(
            allowed = true,
            sourceDeviceId = sourceDeviceId,
            targetDeviceId = targetDevice.deviceId,
            transferredTrack = activeTrack,
            reason = "Metadata verified on '${targetDevice.deviceName}'. Transferred '${activeTrack.title}' at ${sourceSnapshot.positionMs}ms.$skippedSuffix"
        )
    }

    /**
     * Filters [snapshot] queues for a target device according to local metadata availability,
     * stripping tracks not loaded locally unless [allowLanStreamingFallback] is explicitly enabled.
     */
    fun filterSnapshotForTargetDevice(
        snapshot: PlaybackSnapshot,
        targetDevice: ConnectedDeviceNode,
        allowLanStreamingFallback: Boolean = false
    ): Pair<PlaybackSnapshot, Int> {
        if (allowLanStreamingFallback) return snapshot to 0
        val filteredPriority = snapshot.priorityQueue.filter { targetDevice.hasMatchingTrackMetadataLoaded(it) }
        val filteredStandard = snapshot.standardQueue.filter { targetDevice.hasMatchingTrackMetadataLoaded(it) }
        val skipped = (snapshot.priorityQueue.size - filteredPriority.size) +
            (snapshot.standardQueue.size - filteredStandard.size)
        return snapshot.copy(
            priorityQueue = filteredPriority,
            standardQueue = filteredStandard
        ) to skipped
    }

    @Serializable
    data class RemoteLibraryTrackSummary(
        val trackIdentity: String,
        val normalizedKey: String,
        val relativePath: String,
        val title: String,
        val artist: String,
        val album: String,
        val discNumber: Int = 1,
        val trackNumber: Int = 1,
        val durationMs: Long = 0L,
        val audioQuality: AudioQuality? = null
    ) {
        fun toRemoteDescriptor(lanHost: String? = null): RemoteTrackDescriptor {
            val streamUri = if (!lanHost.isNullOrBlank()) {
                "http://$lanHost/stream?path=${java.net.URLEncoder.encode(relativePath, "UTF-8")}"
            } else {
                null
            }
            return RemoteTrackDescriptor(
                relativePathFromRoot = relativePath,
                normalizedTrackKey = normalizedKey,
                durationMs = durationMs,
                title = title,
                artist = artist,
                album = album,
                trackNumber = trackNumber,
                discNumber = discNumber,
                audioQuality = audioQuality,
                lanStreamUri = streamUri
            )
        }

        companion object {
            fun fromTrack(track: Track): RemoteLibraryTrackSummary {
                return RemoteLibraryTrackSummary(
                    trackIdentity = "${track.normalizedKey}|${track.relativePath}",
                    normalizedKey = track.normalizedKey,
                    relativePath = track.relativePath,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    discNumber = track.discNumber,
                    trackNumber = track.trackNumber,
                    durationMs = track.durationMs,
                    audioQuality = track.audioQuality
                )
            }
        }
    }

    @Serializable
    data class PlaylistSyncTrackItem(
        val positionIndex: Int,
        val normalizedKey: String,
        val relativePath: String = "",
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long = 0L
    )

    @Serializable
    data class PlaylistSyncRecord(
        val name: String,
        val clientUpdatedAt: Long = System.currentTimeMillis(),
        val items: List<PlaylistSyncTrackItem> = emptyList()
    )

    @Serializable
    data class QrPairPayload(
        val version: String = PROTOCOL_VERSION,
        val accountId: String,
        val username: String,
        val pairSecretHmac: String,
        val pinCode: String = "",
        val lanHost: String? = null,
        val lanPort: Int = 8765,
        val supabaseUrl: String = "",
        val supabaseAnonKey: String = "",
        val refreshToken: String? = null,
        val issuedAtMs: Long = System.currentTimeMillis()
    ) {
        fun toQrDeepLink(): String {
            val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(this).toByteArray(StandardCharsets.UTF_8))
            return "musika://pair?p=$encoded"
        }

        fun toLanHttpUrl(): String? {
            if (lanHost.isNullOrBlank()) return null
            val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(this).toByteArray(StandardCharsets.UTF_8))
            return "http://$lanHost:$lanPort/#pair=$encoded"
        }

        companion object {
            fun parse(rawInput: String): QrPairPayload? {
                val trimmed = rawInput.trim()
                if (trimmed.isEmpty()) return null
                return runCatching {
                    when {
                        trimmed.startsWith("{") -> json.decodeFromString<QrPairPayload>(trimmed)
                        trimmed.contains("p=") -> {
                            val token = trimmed.substringAfter("p=").substringBefore("&").substringBefore("#")
                            val decoded = String(java.util.Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)
                            json.decodeFromString<QrPairPayload>(decoded)
                        }
                        trimmed.contains("#pair=") -> {
                            val token = trimmed.substringAfter("#pair=").substringBefore("&")
                            val decoded = String(java.util.Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)
                            json.decodeFromString<QrPairPayload>(decoded)
                        }
                        else -> null
                    }
                }.getOrNull()
            }
        }
    }

    /**
     * Unified JSON wire envelope shared between Direct LAN WebSockets and Supabase Realtime Broadcast.
     */
    @Serializable
    data class SyncWireEnvelope(
        val type: String, // "hello", "auth_challenge", "auth_proof", "command", "snapshot", "catalog", "playlists", "sync_mode", "ping", "pong"
        val senderDeviceId: String,
        val senderDeviceName: String = "",
        val senderDeviceType: String = "ANDROID",
        val accountHash: String = "",
        val targetDeviceId: String? = null,
        val timestampMs: Long = System.currentTimeMillis(),
        val nonce: String? = null,
        val hmacProof: String? = null,
        val command: SyncCommand? = null,
        val snapshot: PlaybackSnapshot? = null,
        val catalog: List<RemoteLibraryTrackSummary>? = null,
        val playlists: List<PlaylistSyncRecord>? = null,
        val syncMode: AccountDeviceSyncMode? = null,
        val lanHost: String? = null,
        val lanPort: Int? = null
    )

    fun shortAccountHash(accountId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(accountId.trim().lowercase().toByteArray(StandardCharsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    fun computeHmacHex(secretHexOrRaw: String, message: String): String {
        val keyBytes = secretHexOrRaw.toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    fun verifyHmacHex(secret: String, message: String, candidateHex: String): Boolean {
        if (secret.isBlank() || candidateHex.isBlank()) return false
        val expected = computeHmacHex(secret, message)
        return MessageDigest.isEqual(
            expected.lowercase().toByteArray(StandardCharsets.UTF_8),
            candidateHex.lowercase().toByteArray(StandardCharsets.UTF_8)
        )
    }

    fun serializeEnvelope(envelope: SyncWireEnvelope): String = json.encodeToString(envelope)
    fun deserializeEnvelope(payload: String): SyncWireEnvelope = json.decodeFromString(payload)

    fun serializeSnapshot(snapshot: PlaybackSnapshot): String = json.encodeToString(snapshot)
    fun deserializeSnapshot(payload: String): PlaybackSnapshot = json.decodeFromString(payload)

    fun serializeCommand(command: SyncCommand): String = json.encodeToString(command)
    fun deserializeCommand(payload: String): SyncCommand = json.decodeFromString(payload)
}
