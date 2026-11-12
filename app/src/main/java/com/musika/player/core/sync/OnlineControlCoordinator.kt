package com.musika.player.core.sync

import com.musika.player.core.model.Track
import com.musika.player.player.DualQueueEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stage 2 Online Multi-Device Control & Cross-Device Playback Transfer Coordinator.
 *
 * Implements the three Stage-2 Online Control capabilities:
 * 1. **Remote Control of Active Playing Device:**
 *    Send Play, Pause, TogglePlayPause, SkipNext, SkipPrevious, SeekTo, SetRandom, SetLoop,
 *    Enqueue, and PlayNow commands to the active playing device (or receive them on this device).
 * 2. **Account Device Sync Mode Selection:**
 *    Choose whether devices on the same account stay synchronized ([RemoteSyncContract.AccountDeviceSyncMode.SYNCED_PLAYBACK])
 *    or operate independently at the same time ([RemoteSyncContract.AccountDeviceSyncMode.INDEPENDENT_CONCURRENT]).
 * 3. **Metadata-Gated Music Transfer Between Devices:**
 *    Move active music playback (track, exact `positionMs`, Priority & Standard queues, Random & Loop modes)
 *    from one device to another ONLY if both devices have a song with matching metadata loaded
 *    (`normalizedTrackKey` / `relativePathFromRoot`).
 */
class OnlineControlCoordinator(
    private val localEngine: DualQueueEngine,
    val localDeviceId: String = "android-primary-device",
    val localDeviceName: String = "This Android Phone",
    initialSyncMode: RemoteSyncContract.AccountDeviceSyncMode =
        RemoteSyncContract.AccountDeviceSyncMode.SYNCED_PLAYBACK
) {

    data class OnlineControlState(
        val accountId: String = "musika-account-main",
        val syncMode: RemoteSyncContract.AccountDeviceSyncMode =
            RemoteSyncContract.AccountDeviceSyncMode.SYNCED_PLAYBACK,
        val localDeviceId: String = "android-primary-device",
        val activeControllingDeviceId: String = "android-primary-device",
        val connectedDevices: List<RemoteSyncContract.ConnectedDeviceNode> = emptyList(),
        val lastTransferResult: RemoteSyncContract.PlaybackTransferResult? = null,
        val lastDispatchedCommandJson: String? = null
    )

    private val _state = MutableStateFlow(
        OnlineControlState(
            syncMode = initialSyncMode,
            localDeviceId = localDeviceId,
            activeControllingDeviceId = localDeviceId,
            connectedDevices = listOf(
                RemoteSyncContract.ConnectedDeviceNode(
                    deviceId = localDeviceId,
                    deviceName = localDeviceName,
                    deviceType = "ANDROID",
                    isThisDevice = true,
                    isActivePlayingDevice = true
                ),
                RemoteSyncContract.ConnectedDeviceNode(
                    deviceId = "desktop-winamp-workstation",
                    deviceName = "Desktop PC (Musika Hub)",
                    deviceType = "DESKTOP",
                    isThisDevice = false,
                    isActivePlayingDevice = false
                )
            )
        )
    )
    val state: StateFlow<OnlineControlState> = _state.asStateFlow()

    /**
     * Updates the account-wide multi-device synchronization mode:
     * - [RemoteSyncContract.AccountDeviceSyncMode.SYNCED_PLAYBACK]: keep devices on the same account synced.
     * - [RemoteSyncContract.AccountDeviceSyncMode.INDEPENDENT_CONCURRENT]: allow 2+ devices of the same account
     *   to play different tracks simultaneously.
     */
    fun setAccountSyncMode(mode: RemoteSyncContract.AccountDeviceSyncMode) {
        val cmdJson = RemoteSyncContract.serializeCommand(
            RemoteSyncContract.SyncCommand.SetAccountSyncMode(mode)
        )
        _state.value = _state.value.copy(
            syncMode = mode,
            lastDispatchedCommandJson = cmdJson
        )
    }

    /**
     * Registers or updates a peer device on the same account, including the set of track metadata keys
     * and relative paths loaded in its library/queue so cross-device hand-off can verify metadata availability.
     */
    fun registerOrUpdatePeerDevice(
        deviceId: String,
        deviceName: String,
        deviceType: String = "DESKTOP",
        loadedTracks: List<Track> = emptyList(),
        snapshot: RemoteSyncContract.PlaybackSnapshot? = null
    ) {
        val current = _state.value
        val normKeys = loadedTracks.map { it.normalizedKey.lowercase() }.toSet()
        val relPaths = loadedTracks.map { it.relativePath }.filter { it.isNotBlank() }.toSet()
        val updatedNode = RemoteSyncContract.ConnectedDeviceNode(
            deviceId = deviceId,
            deviceName = deviceName,
            deviceType = deviceType,
            accountId = current.accountId,
            isThisDevice = deviceId == localDeviceId,
            isActivePlayingDevice = current.activeControllingDeviceId == deviceId,
            lastSnapshot = snapshot,
            loadedTrackKeys = normKeys,
            loadedRelativePaths = relPaths
        )
        val others = current.connectedDevices.filterNot { it.deviceId == deviceId }
        _state.value = current.copy(connectedDevices = others + updatedNode)
    }

    /**
     * Synchronizes a peer device's loaded catalog with the local library (e.g., when both devices
     * share the same Musical Root Folder or paired library catalog).
     */
    fun mirrorLocalCatalogToPeerDevice(targetDeviceId: String, localTracks: List<Track>) {
        val current = _state.value
        val normKeys = localTracks.map { it.normalizedKey.lowercase() }.toSet()
        val relPaths = localTracks.map { it.relativePath }.filter { it.isNotBlank() }.toSet()
        val updated = current.connectedDevices.map { dev ->
            if (dev.deviceId == targetDeviceId) {
                dev.copy(
                    loadedTrackKeys = normKeys,
                    loadedRelativePaths = relPaths
                )
            } else {
                dev
            }
        }
        _state.value = current.copy(connectedDevices = updated)
    }

    /**
     * Remotely controls the currently active playing device (or both devices if [AccountDeviceSyncMode.SYNCED_PLAYBACK]).
     */
    fun sendRemoteCommand(
        command: RemoteSyncContract.SyncCommand,
        localLibrary: List<Track> = emptyList(),
        targetDeviceId: String = _state.value.activeControllingDeviceId
    ): String {
        val payload = RemoteSyncContract.serializeCommand(command)
        val current = _state.value

        // Apply to local engine if targeting this device OR if account is in SYNCED_PLAYBACK mode
        if (targetDeviceId == localDeviceId ||
            current.syncMode == RemoteSyncContract.AccountDeviceSyncMode.SYNCED_PLAYBACK
        ) {
            localEngine.applySyncCommand(command, localLibrary)
        }

        // Update peer device snapshot state if targeting a remote peer
        val updatedDevices = current.connectedDevices.map { node ->
            if (node.deviceId == targetDeviceId ||
                current.syncMode == RemoteSyncContract.AccountDeviceSyncMode.SYNCED_PLAYBACK
            ) {
                val baseSnap = node.lastSnapshot ?: localEngine.toPlaybackSnapshot()
                val newSnap = when (command) {
                    is RemoteSyncContract.SyncCommand.Play -> baseSnap.copy(isPlaying = true)
                    is RemoteSyncContract.SyncCommand.Pause -> baseSnap.copy(isPlaying = false)
                    is RemoteSyncContract.SyncCommand.TogglePlayPause -> baseSnap.copy(isPlaying = !baseSnap.isPlaying)
                    is RemoteSyncContract.SyncCommand.SeekTo -> baseSnap.copy(positionMs = command.positionMs)
                    is RemoteSyncContract.SyncCommand.SetRandom -> baseSnap.copy(randomMode = command.mode)
                    is RemoteSyncContract.SyncCommand.SetLoop -> baseSnap.copy(loopMode = command.mode)
                    is RemoteSyncContract.SyncCommand.StateUpdate -> command.snapshot
                    is RemoteSyncContract.SyncCommand.TransferPlayback -> command.snapshot
                    else -> localEngine.toPlaybackSnapshot()
                }
                node.copy(lastSnapshot = newSnap)
            } else {
                node
            }
        }

        _state.value = current.copy(
            connectedDevices = updatedDevices,
            lastDispatchedCommandJson = payload
        )
        return payload
    }

    /**
     * Moves/transfers active music playback from the source device to [targetDeviceId].
     *
     * STRICT RULE: Moving the music from one device to the other can ONLY be done if both
     * devices have a song with the same metadata loaded!
     */
    fun transferPlaybackToDevice(
        targetDeviceId: String,
        localLibrary: List<Track> = emptyList()
    ): RemoteSyncContract.PlaybackTransferResult {
        val current = _state.value
        val sourceDeviceId = current.activeControllingDeviceId
        val sourceNode = current.connectedDevices.firstOrNull { it.deviceId == sourceDeviceId }
        val targetNode = current.connectedDevices.firstOrNull { it.deviceId == targetDeviceId }
            ?: return RemoteSyncContract.PlaybackTransferResult(
                allowed = false,
                sourceDeviceId = sourceDeviceId,
                targetDeviceId = targetDeviceId,
                transferredTrack = null,
                reason = "Target device '$targetDeviceId' not found."
            )

        // Build the latest snapshot from the source device
        val sourceSnapshot = if (sourceDeviceId == localDeviceId) {
            localEngine.toPlaybackSnapshot()
        } else {
            sourceNode?.lastSnapshot ?: localEngine.toPlaybackSnapshot()
        }

        // If target is this local Android phone, populate its loadedTrackKeys from localLibrary + localEngine
        val effectiveTargetNode = if (targetDeviceId == localDeviceId) {
            val allLocalTracks = (localLibrary + localEngine.state.value.standardQueue).distinctBy { it.filePath }
            targetNode.copy(
                loadedTrackKeys = allLocalTracks.map { it.normalizedKey.lowercase() }.toSet(),
                loadedRelativePaths = allLocalTracks.map { it.relativePath }.filter { it.isNotBlank() }.toSet(),
                lastSnapshot = localEngine.toPlaybackSnapshot()
            )
        } else {
            targetNode
        }

        val validation = RemoteSyncContract.validateAndPreparePlaybackTransfer(
            sourceDeviceId = sourceDeviceId,
            sourceSnapshot = sourceSnapshot,
            targetDevice = effectiveTargetNode
        )

        if (!validation.allowed) {
            _state.value = current.copy(lastTransferResult = validation)
            return validation
        }

        val transferCommand = RemoteSyncContract.SyncCommand.TransferPlayback(
            sourceDeviceId = sourceDeviceId,
            targetDeviceId = targetDeviceId,
            snapshot = sourceSnapshot.copy(isPlaying = true),
            pauseSourceDevice = current.syncMode == RemoteSyncContract.AccountDeviceSyncMode.INDEPENDENT_CONCURRENT ||
                    sourceDeviceId != targetDeviceId
        )
        val cmdJson = RemoteSyncContract.serializeCommand(transferCommand)

        // If transferring away from this phone in independent mode, pause local playback while handing off
        if (sourceDeviceId == localDeviceId && targetDeviceId != localDeviceId) {
            localEngine.setPlaying(false)
        } else if (targetDeviceId == localDeviceId) {
            localEngine.applySyncCommand(transferCommand, localLibrary)
        }

        val updatedDevices = current.connectedDevices.map { dev ->
            when (dev.deviceId) {
                targetDeviceId -> dev.copy(
                    isActivePlayingDevice = true,
                    lastSnapshot = sourceSnapshot.copy(isPlaying = true)
                )
                sourceDeviceId -> dev.copy(
                    isActivePlayingDevice = false,
                    lastSnapshot = sourceSnapshot.copy(isPlaying = false)
                )
                else -> dev.copy(isActivePlayingDevice = false)
            }
        }

        _state.value = current.copy(
            activeControllingDeviceId = targetDeviceId,
            connectedDevices = updatedDevices,
            lastTransferResult = validation,
            lastDispatchedCommandJson = cmdJson
        )
        return validation
    }
}
