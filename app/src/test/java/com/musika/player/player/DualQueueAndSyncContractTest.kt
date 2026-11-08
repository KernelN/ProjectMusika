package com.musika.player.player

import com.musika.player.core.model.AudioQuality
import com.musika.player.core.model.LoopMode
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.Track
import com.musika.player.core.sync.RemoteSyncContract
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualQueueAndSyncContractTest {

    private val song1 = makeTrack("Song 1", "Artist A", "Album X", 1)
    private val song2 = makeTrack("Song 2", "Artist A", "Album X", 2)
    private val song3 = makeTrack("Song 3", "Artist A", "Album X", 3)
    private val song4 = makeTrack("Song 4", "Artist B", "Album Y", 1)
    private val immediate1 = makeTrack("Immediate 1", "Priority", "FIFO", 1)
    private val immediate2 = makeTrack("Immediate 2", "Priority", "FIFO", 2)

    @Test
    fun `random and loop modes NEVER reset across Song Album Artist and Folder context switches`() {
        val engine = DualQueueEngine(
            initialRandomMode = RandomMode.SHUFFLE_POOL,
            initialLoopMode = LoopMode.QUEUE_LOOP,
            randomGenerator = Random(12345)
        )

        // Switch 1: Play from Songs context
        engine.playFromContext(song2, listOf(song1, song2, song3), "Songs")
        assertEquals(RandomMode.SHUFFLE_POOL, engine.state.value.randomMode)
        assertEquals(LoopMode.QUEUE_LOOP, engine.state.value.loopMode)
        assertEquals("Tapped track MUST play first even when Random is active", song2.filePath, engine.state.value.currentTrack?.filePath)

        // Switch 2: Switch to Album context
        engine.playFromContext(song1, listOf(song1, song2, song3), "Album X")
        assertEquals(RandomMode.SHUFFLE_POOL, engine.state.value.randomMode)
        assertEquals(LoopMode.QUEUE_LOOP, engine.state.value.loopMode)
        assertEquals(song1.filePath, engine.state.value.currentTrack?.filePath)

        // Cycle to TRUE_RANDOM & FILE_LOOP
        engine.cycleRandomMode() // -> TRUE_RANDOM
        engine.cycleLoopMode()   // -> FILE_LOOP
        assertEquals(RandomMode.TRUE_RANDOM, engine.state.value.randomMode)
        assertEquals(LoopMode.FILE_LOOP, engine.state.value.loopMode)

        // Switch 3: Switch to Folder context
        engine.playFromContext(song4, listOf(song4, song1), "Folder /Music")
        assertEquals("TRUE_RANDOM must persist across Folder switch", RandomMode.TRUE_RANDOM, engine.state.value.randomMode)
        assertEquals("FILE_LOOP must persist across Folder switch", LoopMode.FILE_LOOP, engine.state.value.loopMode)
        assertEquals(song4.filePath, engine.state.value.currentTrack?.filePath)
    }

    @Test
    fun `dual queue drains Play Next priority queue in strict FIFO order before standard or random tracks`() {
        val engine = DualQueueEngine(
            initialRandomMode = RandomMode.TRUE_RANDOM,
            initialLoopMode = LoopMode.NO_LOOP,
            randomGenerator = Random(99)
        )

        engine.playFromContext(song1, listOf(song1, song2, song3), "Songs")
        engine.enqueueImmediate(immediate1)
        engine.enqueueImmediate(immediate2)
        engine.enqueueAtEnd(song4)

        assertEquals(song1.filePath, engine.state.value.currentTrack?.filePath)
        assertEquals(2, engine.state.value.priorityQueue.size)
        assertEquals(4, engine.state.value.standardQueue.size)

        // Next 1 -> MUST be Immediate 1 (FIFO)
        val next1 = engine.skipToNext(autoTriggered = true)
        assertEquals(immediate1.filePath, next1?.filePath)

        // Next 2 -> MUST be Immediate 2 (FIFO)
        val next2 = engine.skipToNext(autoTriggered = true)
        assertEquals(immediate2.filePath, next2?.filePath)
        assertTrue(engine.state.value.priorityQueue.isEmpty())

        // Next 3 -> Priority queue is empty; TRUE_RANDOM continues indefinitely even when LoopMode is NO_LOOP
        repeat(10) {
            val nextRandom = engine.skipToNext(autoTriggered = true)
            assertNotNull("TRUE_RANDOM must ignore NO_LOOP stop limits", nextRandom)
        }
    }

    @Test
    fun `shufflePool does not repeat tracks within a cycle and stops on NO_LOOP at end of pool`() {
        val engine = DualQueueEngine(
            initialRandomMode = RandomMode.SHUFFLE_POOL,
            initialLoopMode = LoopMode.NO_LOOP,
            randomGenerator = Random(7)
        )
        val context = listOf(song1, song2, song3)
        engine.playFromContext(song1, context, "Album")

        val played = mutableListOf(engine.state.value.currentTrack!!.filePath)
        val second = engine.skipToNext(autoTriggered = true)
        assertNotNull(second)
        played.add(second!!.filePath)

        val third = engine.skipToNext(autoTriggered = true)
        assertNotNull(third)
        played.add(third!!.filePath)

        assertEquals("All 3 tracks in shuffle pool must be unique", 3, played.toSet().size)

        val fourth = engine.skipToNext(autoTriggered = true)
        assertNull("SHUFFLE_POOL with NO_LOOP must stop after exhausting pool", fourth)
    }

    @Test
    fun `remoteSyncContract serializes state and resolves Local-Match-First with LAN stream fallback`() {
        val engine = DualQueueEngine(
            initialRandomMode = RandomMode.SHUFFLE_POOL,
            initialLoopMode = LoopMode.QUEUE_LOOP
        )
        engine.playFromContext(song1, listOf(song1, song2), "Album X")
        engine.enqueueImmediate(immediate1)

        val snapshot = engine.toPlaybackSnapshot(lanHost = "192.168.1.50:8080")
        val jsonPayload = RemoteSyncContract.serializeSnapshot(snapshot)
        val decoded = RemoteSyncContract.deserializeSnapshot(jsonPayload)

        assertEquals(RandomMode.SHUFFLE_POOL, decoded.randomMode)
        assertEquals(LoopMode.QUEUE_LOOP, decoded.loopMode)
        assertEquals(1, decoded.priorityQueue.size)
        assertNotNull(decoded.currentTrack?.lanStreamUri)

        // Verify Local-Match-First resolution
        val descriptor = decoded.currentTrack!!
        assertTrue(
            "Must match exact relativePathFromRoot",
            descriptor.matchesLocal(song1.relativePath, "different|key|here", 0L)
        )
        assertTrue(
            "Must match normalizedTrackKey within 2000ms tolerance",
            descriptor.matchesLocal("other/path.mp3", song1.normalizedKey, song1.durationMs + 1500L)
        )
    }

    private fun makeTrack(title: String, artist: String, album: String, trackNum: Int): Track {
        val slug = title.lowercase().replace(" ", "_")
        return Track(
            filePath = "/music/$slug.flac",
            relativePath = "album/$slug.flac",
            fileName = "$slug.flac",
            fileExtension = "flac",
            title = title,
            artist = artist,
            album = album,
            trackNumber = trackNum,
            durationMs = 200_000L,
            audioQuality = AudioQuality("FLAC", 950, 44100, 16, true)
        )
    }
}
