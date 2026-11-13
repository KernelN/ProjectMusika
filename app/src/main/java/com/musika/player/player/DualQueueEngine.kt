package com.musika.player.player

import com.musika.player.core.model.LoopMode
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.Track
import com.musika.player.core.sync.RemoteSyncContract
import com.musika.player.data.scanner.MultiDiscDetector
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dual-Queue Playback & Immutable 3-State Random / 3-State Loop State Machine (Specification #5, #8, #9).
 *
 * Guarantees:
 * 1. Dual Queue:
 *    - Priority Queue ("Play Next" — Spotify FIFO style): drains in strict FIFO order
 *      (Current -> Immediate1 -> Immediate2) immediately after the current track.
 *    - Standard Queue ("Add at End" — Winamp style): appends to the bottom of the standard queue.
 * 2. 3-State Random:
 *    - OFF: sequential playback in natural order.
 *    - SHUFFLE_POOL: non-repeating shuffle across the active context.
 *    - TRUE_RANDOM: memoryless random selection on every track change, ignoring loop limits
 *      while still draining the Priority Queue first.
 * 3. 3-State Loop (Spotify parity):
 *    - NO_LOOP: stops when queue reaches the end (unless TRUE_RANDOM is active).
 *    - QUEUE_LOOP: repeats the active queue/context indefinitely.
 *    - FILE_LOOP: repeats the single current track on natural track completion.
 * 4. Immutable Persistence:
 *    - Random and Loop modes NEVER reset when switching songs, albums, artists, or folders.
 *    - When Random is active and the user taps a specific track, that track plays first,
 *      followed by randomized tracks from that context.
 */
class DualQueueEngine(
    initialRandomMode: RandomMode = RandomMode.OFF,
    initialLoopMode: LoopMode = LoopMode.NO_LOOP,
    private val randomGenerator: Random = Random.Default,
    private val onModesChanged: ((RandomMode, LoopMode) -> Unit)? = null
) {

    interface PlaybackDriver {
        fun onLoadAndPlayTrack(track: Track, startPositionMs: Long = 0L, playWhenReady: Boolean = true)
        fun onPlayStateChanged(isPlaying: Boolean)
        fun onSeekTo(positionMs: Long)
    }

    @Volatile
    var playbackDriver: PlaybackDriver? = null

    data class EngineState(
        val currentTrack: Track? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val randomMode: RandomMode = RandomMode.OFF,
        val loopMode: LoopMode = LoopMode.NO_LOOP,
        val priorityQueue: List<Track> = emptyList(), // FIFO "Play Next"
        val standardQueue: List<Track> = emptyList(), // Standard Context Queue ("Add at End")
        val shufflePool: List<Track> = emptyList(),   // Non-repeating shuffled order when SHUFFLE_POOL is active
        val activeContextName: String? = null
    )

    private val _state = MutableStateFlow(
        EngineState(
            randomMode = initialRandomMode,
            loopMode = initialLoopMode
        )
    )
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val playHistory = ArrayDeque<Track>()

    /**
     * Starts playback of [selectedTrack] within [contextTracks] (Song, Album, Artist, or Folder).
     * NEVER resets [RandomMode] or [LoopMode].
     * When Random is active, [selectedTrack] plays first, followed by randomized tracks from [contextTracks].
     */
    fun playFromContext(
        selectedTrack: Track,
        contextTracks: List<Track>,
        contextName: String? = null
    ) {
        val current = _state.value
        val effectiveContext = if (contextTracks.isEmpty()) {
            listOf(selectedTrack)
        } else if (contextTracks.none { it.filePath == selectedTrack.filePath }) {
            listOf(selectedTrack) + contextTracks
        } else {
            contextTracks
        }

        val pool = buildShufflePoolWithSelectedFirst(selectedTrack, effectiveContext)

        current.currentTrack?.let { prev ->
            if (prev.filePath != selectedTrack.filePath) {
                playHistory.addLast(prev)
            }
        }

        // Notice: randomMode and loopMode are strictly preserved!
        _state.value = current.copy(
            currentTrack = selectedTrack,
            isPlaying = true,
            positionMs = 0L,
            standardQueue = effectiveContext,
            shufflePool = pool,
            activeContextName = contextName
        )
        playbackDriver?.onLoadAndPlayTrack(selectedTrack, 0L, true)
    }

    /**
     * Multi-Disc Playback ("Play All Discs" — Specification #5):
     * - If Random is OFF: plays sequentially from Disc 1 (Track 1..N) -> Disc 2 (Track 1..N).
     * - If Random is active: randomizes across all discs combined.
     * NEVER resets RandomMode or LoopMode.
     */
    fun playAllDiscs(allDiscTracks: List<Track>, albumOrFolderName: String) {
        if (allDiscTracks.isEmpty()) return
        val current = _state.value
        val ordered = MultiDiscDetector.orderTracksForPlayAllDiscs(
            tracks = allDiscTracks,
            randomMode = current.randomMode,
            random = randomGenerator
        )
        val firstTrack = ordered.first()
        val sequentialStandard = MultiDiscDetector.orderTracksForPlayAllDiscs(
            tracks = allDiscTracks,
            randomMode = RandomMode.OFF,
            random = randomGenerator
        )
        _state.value = current.copy(
            currentTrack = firstTrack,
            isPlaying = true,
            positionMs = 0L,
            standardQueue = sequentialStandard,
            shufflePool = ordered,
            activeContextName = "$albumOrFolderName (All Discs)"
        )
        playbackDriver?.onLoadAndPlayTrack(firstTrack, 0L, true)
    }

    /**
     * Add Immediate ("Play Next" — Spotify FIFO Priority Queue).
     * Plays immediately after the current track in the exact FIFO order queued:
     * Current -> Immediate1 -> Immediate2.
     */
    fun enqueueImmediate(track: Track) {
        val current = _state.value
        if (current.currentTrack == null) {
            _state.value = current.copy(
                currentTrack = track,
                isPlaying = true,
                positionMs = 0L,
                standardQueue = listOf(track),
                shufflePool = listOf(track)
            )
            playbackDriver?.onLoadAndPlayTrack(track, 0L, true)
            return
        }
        _state.value = current.copy(
            priorityQueue = current.priorityQueue + track
        )
    }

    /**
     * Add at End (Winamp style).
     * Appends to the bottom of the standard queue (and shuffle pool).
     */
    fun enqueueAtEnd(track: Track) {
        val current = _state.value
        if (current.currentTrack == null) {
            _state.value = current.copy(
                currentTrack = track,
                isPlaying = true,
                positionMs = 0L,
                standardQueue = listOf(track),
                shufflePool = listOf(track)
            )
            playbackDriver?.onLoadAndPlayTrack(track, 0L, true)
            return
        }
        _state.value = current.copy(
            standardQueue = current.standardQueue + track,
            shufflePool = current.shufflePool + track
        )
    }

    /**
     * Cycles or sets the 3-State Random mode (OFF -> SHUFFLE_POOL -> TRUE_RANDOM -> OFF).
     */
    fun cycleRandomMode(): RandomMode {
        val nextMode = _state.value.randomMode.next()
        setRandomMode(nextMode)
        return nextMode
    }

    fun setRandomMode(mode: RandomMode) {
        val current = _state.value
        val activeTrack = current.currentTrack
        val rebuiltPool = if (activeTrack != null && current.standardQueue.isNotEmpty()) {
            buildShufflePoolWithSelectedFirst(activeTrack, current.standardQueue)
        } else {
            current.standardQueue.shuffled(randomGenerator)
        }
        _state.value = current.copy(
            randomMode = mode,
            shufflePool = rebuiltPool
        )
        onModesChanged?.invoke(mode, _state.value.loopMode)
    }

    /**
     * Cycles or sets the 3-State Loop mode (NO_LOOP -> QUEUE_LOOP -> FILE_LOOP -> NO_LOOP).
     */
    fun cycleLoopMode(): LoopMode {
        val nextMode = _state.value.loopMode.next()
        setLoopMode(nextMode)
        return nextMode
    }

    fun setLoopMode(mode: LoopMode) {
        val current = _state.value
        _state.value = current.copy(loopMode = mode)
        onModesChanged?.invoke(_state.value.randomMode, mode)
    }

    /**
     * Advances to the next track according to the Priority Queue, RandomMode, and LoopMode rules.
     * @param autoTriggered true when called automatically at the end of a track; false when user taps Next.
     */
    fun skipToNext(autoTriggered: Boolean = false): Track? {
        val current = _state.value
        val activeTrack = current.currentTrack ?: return null

        // 1. FILE_LOOP repeats the single playing file when auto-triggered (or if it's the only track)
        if (current.loopMode == LoopMode.FILE_LOOP && autoTriggered) {
            _state.value = current.copy(positionMs = 0L, isPlaying = true)
            playbackDriver?.onSeekTo(0L)
            playbackDriver?.onPlayStateChanged(true)
            return activeTrack
        }

        // 2. Priority Queue ("Play Next" FIFO) ALWAYS drains first before Standard Queue / Shuffle / True Random!
        if (current.priorityQueue.isNotEmpty()) {
            val nextImmediate = current.priorityQueue.first()
            val remainingPriority = current.priorityQueue.drop(1)
            playHistory.addLast(activeTrack)
            _state.value = current.copy(
                currentTrack = nextImmediate,
                priorityQueue = remainingPriority,
                positionMs = 0L,
                isPlaying = true
            )
            playbackDriver?.onLoadAndPlayTrack(nextImmediate, 0L, true)
            return nextImmediate
        }

        if (current.standardQueue.isEmpty()) {
            _state.value = current.copy(isPlaying = false, positionMs = 0L)
            playbackDriver?.onPlayStateChanged(false)
            return null
        }

        // 3. TRUE_RANDOM: memoryless random selection on every track change, ignoring loop limits!
        if (current.randomMode == RandomMode.TRUE_RANDOM) {
            val randomIndex = randomGenerator.nextInt(current.standardQueue.size)
            val nextRandomTrack = current.standardQueue[randomIndex]
            playHistory.addLast(activeTrack)
            _state.value = current.copy(
                currentTrack = nextRandomTrack,
                positionMs = 0L,
                isPlaying = true
            )
            playbackDriver?.onLoadAndPlayTrack(nextRandomTrack, 0L, true)
            return nextRandomTrack
        }

        // 4. SHUFFLE_POOL: non-repeating shuffle pool
        if (current.randomMode == RandomMode.SHUFFLE_POOL) {
            val pool = current.shufflePool.ifEmpty {
                buildShufflePoolWithSelectedFirst(activeTrack, current.standardQueue)
            }
            val currentIdx = pool.indexOfFirst { it.filePath == activeTrack.filePath }
            val nextIdx = currentIdx + 1

            return if (nextIdx in pool.indices) {
                val nextTrack = pool[nextIdx]
                playHistory.addLast(activeTrack)
                _state.value = current.copy(
                    currentTrack = nextTrack,
                    shufflePool = pool,
                    positionMs = 0L,
                    isPlaying = true
                )
                playbackDriver?.onLoadAndPlayTrack(nextTrack, 0L, true)
                nextTrack
            } else if (current.loopMode == LoopMode.QUEUE_LOOP || current.loopMode == LoopMode.FILE_LOOP) {
                val reshuffled = current.standardQueue.shuffled(randomGenerator)
                val nextTrack = reshuffled.first()
                playHistory.addLast(activeTrack)
                _state.value = current.copy(
                    currentTrack = nextTrack,
                    shufflePool = reshuffled,
                    positionMs = 0L,
                    isPlaying = true
                )
                playbackDriver?.onLoadAndPlayTrack(nextTrack, 0L, true)
                nextTrack
            } else {
                // NO_LOOP at end of shuffle pool -> stop
                _state.value = current.copy(isPlaying = false, positionMs = 0L)
                playbackDriver?.onPlayStateChanged(false)
                null
            }
        }

        // 5. RandomMode.OFF: sequential order in standardQueue
        val currentIdx = current.standardQueue.indexOfFirst { it.filePath == activeTrack.filePath }
        val nextIdx = currentIdx + 1
        return if (nextIdx in current.standardQueue.indices) {
            val nextTrack = current.standardQueue[nextIdx]
            playHistory.addLast(activeTrack)
            _state.value = current.copy(
                currentTrack = nextTrack,
                positionMs = 0L,
                isPlaying = true
            )
            playbackDriver?.onLoadAndPlayTrack(nextTrack, 0L, true)
            nextTrack
        } else if (current.loopMode == LoopMode.QUEUE_LOOP || current.loopMode == LoopMode.FILE_LOOP) {
            val firstTrack = current.standardQueue.first()
            playHistory.addLast(activeTrack)
            _state.value = current.copy(
                currentTrack = firstTrack,
                positionMs = 0L,
                isPlaying = true
            )
            playbackDriver?.onLoadAndPlayTrack(firstTrack, 0L, true)
            firstTrack
        } else {
            _state.value = current.copy(isPlaying = false, positionMs = 0L)
            playbackDriver?.onPlayStateChanged(false)
            null
        }
    }

    fun skipToPrevious(): Track? {
        val current = _state.value
        if (current.positionMs > 3000L) {
            _state.value = current.copy(positionMs = 0L)
            playbackDriver?.onSeekTo(0L)
            return current.currentTrack
        }
        if (playHistory.isNotEmpty()) {
            val prev = playHistory.removeLast()
            _state.value = current.copy(
                currentTrack = prev,
                positionMs = 0L,
                isPlaying = true
            )
            playbackDriver?.onLoadAndPlayTrack(prev, 0L, true)
            return prev
        }
        val activeTrack = current.currentTrack ?: return null
        val idx = current.standardQueue.indexOfFirst { it.filePath == activeTrack.filePath }
        if (idx > 0) {
            val prev = current.standardQueue[idx - 1]
            _state.value = current.copy(
                currentTrack = prev,
                positionMs = 0L,
                isPlaying = true
            )
            playbackDriver?.onLoadAndPlayTrack(prev, 0L, true)
            return prev
        }
        _state.value = current.copy(positionMs = 0L)
        playbackDriver?.onSeekTo(0L)
        return activeTrack
    }

    fun setPlaying(playing: Boolean) {
        _state.value = _state.value.copy(isPlaying = playing)
        playbackDriver?.onPlayStateChanged(playing)
    }

    fun togglePlayPause() {
        val current = _state.value
        if (current.currentTrack != null) {
            val nextPlaying = !current.isPlaying
            _state.value = current.copy(isPlaying = nextPlaying)
            playbackDriver?.onPlayStateChanged(nextPlaying)
        }
    }

    fun seekTo(positionMs: Long) {
        val current = _state.value
        val maxDur = current.currentTrack?.durationMs?.takeIf { it > 0L } ?: Long.MAX_VALUE
        val clamped = positionMs.coerceIn(0L, maxDur.coerceAtLeast(0L))
        _state.value = current.copy(positionMs = clamped)
        playbackDriver?.onSeekTo(clamped)
    }

    /**
     * Called by the active media player ticker to update live playback position and decoded track duration
     * without re-triggering a user seek command.
     */
    fun updateProgressFromPlayer(positionMs: Long, actualDurationMs: Long = 0L) {
        val current = _state.value
        val active = current.currentTrack ?: return
        val updatedTrack = if (actualDurationMs > 0L && actualDurationMs != active.durationMs) {
            active.copy(durationMs = actualDurationMs)
        } else {
            active
        }
        val clampedPos = positionMs.coerceAtLeast(0L)
        if (current.positionMs != clampedPos || updatedTrack !== active) {
            _state.value = current.copy(
                currentTrack = updatedTrack,
                positionMs = clampedPos
            )
        }
    }

    fun removeFromPriorityQueue(index: Int) {
        val current = _state.value
        if (index in current.priorityQueue.indices) {
            val mutable = current.priorityQueue.toMutableList()
            mutable.removeAt(index)
            _state.value = current.copy(priorityQueue = mutable)
        }
    }

    fun movePriorityQueueTrack(fromIndex: Int, toIndex: Int) {
        val current = _state.value
        if (fromIndex in current.priorityQueue.indices &&
            toIndex in current.priorityQueue.indices &&
            fromIndex != toIndex
        ) {
            val mutable = current.priorityQueue.toMutableList()
            val moved = mutable.removeAt(fromIndex)
            mutable.add(toIndex, moved)
            _state.value = current.copy(priorityQueue = mutable)
        }
    }

    fun clearPriorityQueue() {
        _state.value = _state.value.copy(priorityQueue = emptyList())
    }

    /**
     * Returns whether the upcoming context queue is visible and freely modifiable.
     * Per user specification: the Queue is visible and modifiable in all modes EXCEPT
     * when [RandomMode.TRUE_RANDOM] is active (since True Random selects memorylessly on each step).
     */
    fun isContextQueueModifiable(): Boolean {
        return _state.value.randomMode != RandomMode.TRUE_RANDOM
    }

    /**
     * Returns the active upcoming context queue order:
     * - [RandomMode.OFF]: [EngineState.standardQueue]
     * - [RandomMode.SHUFFLE_POOL]: [EngineState.shufflePool] (or standardQueue if pool not yet built)
     * - [RandomMode.TRUE_RANDOM]: emptyList() (memoryless random)
     */
    fun getVisibleContextQueue(): List<Track> {
        val current = _state.value
        return when (current.randomMode) {
            RandomMode.OFF -> current.standardQueue
            RandomMode.SHUFFLE_POOL -> current.shufflePool.ifEmpty { current.standardQueue }
            RandomMode.TRUE_RANDOM -> emptyList()
        }
    }

    /**
     * Moves a track inside the active context queue (Standard Queue when OFF, Shuffle Pool when SHUFFLE_POOL).
     * No-op if [RandomMode.TRUE_RANDOM] is active.
     */
    fun moveContextQueueTrack(fromIndex: Int, toIndex: Int) {
        val current = _state.value
        if (current.randomMode == RandomMode.TRUE_RANDOM) return

        if (current.randomMode == RandomMode.SHUFFLE_POOL) {
            val pool = current.shufflePool.ifEmpty { current.standardQueue }.toMutableList()
            if (fromIndex in pool.indices && toIndex in pool.indices && fromIndex != toIndex) {
                val moved = pool.removeAt(fromIndex)
                pool.add(toIndex, moved)
                _state.value = current.copy(shufflePool = pool)
            }
        } else {
            val std = current.standardQueue.toMutableList()
            if (fromIndex in std.indices && toIndex in std.indices && fromIndex != toIndex) {
                val moved = std.removeAt(fromIndex)
                std.add(toIndex, moved)
                _state.value = current.copy(standardQueue = std)
            }
        }
    }

    /**
     * Removes a track at [index] from the active context queue (Standard Queue and Shuffle Pool).
     * No-op if [RandomMode.TRUE_RANDOM] is active.
     */
    fun removeFromContextQueue(index: Int) {
        val current = _state.value
        if (current.randomMode == RandomMode.TRUE_RANDOM) return

        if (current.randomMode == RandomMode.SHUFFLE_POOL) {
            val pool = current.shufflePool.ifEmpty { current.standardQueue }.toMutableList()
            if (index in pool.indices) {
                val removed = pool.removeAt(index)
                val updatedStandard = current.standardQueue.filterNot { it.filePath == removed.filePath }
                _state.value = current.copy(
                    shufflePool = pool,
                    standardQueue = updatedStandard
                )
            }
        } else {
            val std = current.standardQueue.toMutableList()
            if (index in std.indices) {
                val removed = std.removeAt(index)
                val updatedPool = current.shufflePool.filterNot { it.filePath == removed.filePath }
                _state.value = current.copy(
                    standardQueue = std,
                    shufflePool = updatedPool
                )
            }
        }
    }

    /**
     * Clears the upcoming context queue (keeping the currently playing track if any).
     */
    fun clearContextQueue() {
        val current = _state.value
        if (current.randomMode == RandomMode.TRUE_RANDOM) return
        val keep = current.currentTrack?.let { listOf(it) } ?: emptyList()
        _state.value = current.copy(
            standardQueue = keep,
            shufflePool = keep
        )
    }

    /**
     * Exports the current Queue (Priority Queue + active Context Queue in current playback order)
     * as an ordered list of [Track]s ready to be saved as a Playlist.
     */
    fun exportQueueTracksForPlaylist(): List<Track> {
        val current = _state.value
        val result = mutableListOf<Track>()
        current.currentTrack?.let { result.add(it) }
        result.addAll(current.priorityQueue)
        if (current.randomMode != RandomMode.TRUE_RANDOM) {
            val contextList = getVisibleContextQueue()
            for (t in contextList) {
                if (result.none { it.filePath == t.filePath }) {
                    result.add(t)
                }
            }
        }
        return result
    }

    /**
     * Stage 2 Desktop Sync Contract integration: exports serializable Local-Match-First snapshot.
     */
    fun toPlaybackSnapshot(lanHost: String? = null): RemoteSyncContract.PlaybackSnapshot {
        val s = _state.value
        return RemoteSyncContract.PlaybackSnapshot(
            isPlaying = s.isPlaying,
            positionMs = s.positionMs,
            currentTrack = s.currentTrack?.let { RemoteSyncContract.RemoteTrackDescriptor.fromTrack(it, lanHost) },
            randomMode = s.randomMode,
            loopMode = s.loopMode,
            priorityQueue = s.priorityQueue.map { RemoteSyncContract.RemoteTrackDescriptor.fromTrack(it, lanHost) },
            standardQueue = s.standardQueue.map { RemoteSyncContract.RemoteTrackDescriptor.fromTrack(it, lanHost) },
            activeContextName = s.activeContextName
        )
    }

    /**
     * Resolves and executes a [RemoteSyncContract.SyncCommand] using Local-Match-First against [localLibrary].
     */
    fun applySyncCommand(
        command: RemoteSyncContract.SyncCommand,
        localLibrary: List<Track> = emptyList(),
        allowLanStreaming: Boolean = false,
        playAudio: Boolean = true
    ) {
        when (command) {
            is RemoteSyncContract.SyncCommand.Play -> {
                if (playAudio) setPlaying(true) else _state.value = _state.value.copy(isPlaying = true)
            }
            is RemoteSyncContract.SyncCommand.Pause -> setPlaying(false)
            is RemoteSyncContract.SyncCommand.TogglePlayPause -> {
                if (playAudio) togglePlayPause() else _state.value = _state.value.copy(isPlaying = !_state.value.isPlaying)
            }
            is RemoteSyncContract.SyncCommand.SeekTo -> {
                if (playAudio) seekTo(command.positionMs) else _state.value = _state.value.copy(positionMs = command.positionMs)
            }
            is RemoteSyncContract.SyncCommand.SkipNext -> skipToNext(autoTriggered = false)
            is RemoteSyncContract.SyncCommand.SkipPrevious -> skipToPrevious()
            is RemoteSyncContract.SyncCommand.SetRandom -> setRandomMode(command.mode)
            is RemoteSyncContract.SyncCommand.SetLoop -> setLoopMode(command.mode)
            is RemoteSyncContract.SyncCommand.Enqueue -> {
                val matched = resolveRemoteDescriptor(command.track, localLibrary, allowLanStreaming)
                if (command.immediate) enqueueImmediate(matched) else enqueueAtEnd(matched)
            }
            is RemoteSyncContract.SyncCommand.PlayNow -> {
                val matchedPrimary = resolveRemoteDescriptor(command.track, localLibrary, allowLanStreaming)
                val matchedContext = command.contextTracks.map {
                    resolveRemoteDescriptor(it, localLibrary, allowLanStreaming)
                }
                if (playAudio) {
                    playFromContext(matchedPrimary, matchedContext, "Remote Sync")
                } else {
                    _state.value = _state.value.copy(
                        currentTrack = matchedPrimary,
                        isPlaying = true,
                        positionMs = 0L,
                        standardQueue = matchedContext,
                        activeContextName = "Remote Sync"
                    )
                    playbackDriver?.onPlayStateChanged(false)
                }
            }
            is RemoteSyncContract.SyncCommand.StateUpdate -> {
                applyRemoteSnapshot(command.snapshot, localLibrary, allowLanStreaming, playAudio)
            }
            is RemoteSyncContract.SyncCommand.SetAccountSyncMode -> {
                // Handled at OnlineControlCoordinator level
            }
            is RemoteSyncContract.SyncCommand.TransferPlayback -> {
                applyRemoteSnapshot(command.snapshot, localLibrary, allowLanStreaming, playAudio)
            }
        }
    }

    private fun applyRemoteSnapshot(
        snap: RemoteSyncContract.PlaybackSnapshot,
        localLibrary: List<Track>,
        allowLanStreaming: Boolean = false,
        playAudio: Boolean = true
    ) {
        setRandomMode(snap.randomMode)
        setLoopMode(snap.loopMode)
        val matchedCurrent = snap.currentTrack?.let {
            resolveRemoteDescriptor(it, localLibrary, allowLanStreaming)
        }
        val matchedPriority = snap.priorityQueue.map {
            resolveRemoteDescriptor(it, localLibrary, allowLanStreaming)
        }
        val matchedStandard = snap.standardQueue.map {
            resolveRemoteDescriptor(it, localLibrary, allowLanStreaming)
        }
        _state.value = _state.value.copy(
            currentTrack = matchedCurrent,
            isPlaying = snap.isPlaying,
            positionMs = snap.positionMs,
            priorityQueue = matchedPriority,
            standardQueue = matchedStandard,
            activeContextName = snap.activeContextName
        )
        if (playAudio && matchedCurrent != null) {
            playbackDriver?.onLoadAndPlayTrack(matchedCurrent, snap.positionMs, snap.isPlaying)
        } else {
            playbackDriver?.onPlayStateChanged(false)
        }
    }

    private fun resolveRemoteDescriptor(
        descriptor: RemoteSyncContract.RemoteTrackDescriptor,
        localLibrary: List<Track>,
        allowLanStreaming: Boolean = false
    ): Track {
        val localMatch = localLibrary.firstOrNull { local ->
            descriptor.matchesLocal(local.relativePath, local.normalizedKey, local.durationMs)
        }
        if (localMatch != null) return localMatch

        // Only stream over LAN if explicitly enabled in Settings (OFF by default)
        val fallbackPath = if (allowLanStreaming) {
            descriptor.lanStreamUri ?: descriptor.relativePathFromRoot
        } else {
            descriptor.relativePathFromRoot
        }
        return Track(
            filePath = fallbackPath,
            relativePath = descriptor.relativePathFromRoot,
            fileName = descriptor.title,
            fileExtension = descriptor.audioQuality?.format?.lowercase() ?: "stream",
            title = descriptor.title,
            artist = descriptor.artist,
            album = descriptor.album,
            discNumber = descriptor.discNumber,
            trackNumber = descriptor.trackNumber,
            durationMs = descriptor.durationMs,
            audioQuality = descriptor.audioQuality ?: com.musika.player.core.model.AudioQuality("STREAM", 320)
        )
    }

    private fun buildShufflePoolWithSelectedFirst(selectedTrack: Track, contextTracks: List<Track>): List<Track> {
        val others = contextTracks.filter { it.filePath != selectedTrack.filePath }.shuffled(randomGenerator)
        return listOf(selectedTrack) + others
    }
}
