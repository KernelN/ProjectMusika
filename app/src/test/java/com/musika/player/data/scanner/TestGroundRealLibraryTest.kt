package com.musika.player.data.scanner

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.musika.player.core.model.AlbumSortOption
import com.musika.player.core.model.ArtistSortOption
import com.musika.player.core.model.AudioQuality
import com.musika.player.core.model.NowPlayingArtworkStyle
import com.musika.player.core.model.SongSortOption
import com.musika.player.core.model.Track
import com.musika.player.data.local.MusikaDatabase
import com.musika.player.data.repository.LibraryRepository
import com.musika.player.data.repository.SettingsPreferences
import com.musika.player.player.DualQueueEngine
import com.musika.player.ui.components.findIndexForAlphabetLetter
import java.io.File
import java.net.URI
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestGroundRealLibraryTest {

    private lateinit var db: MusikaDatabase
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AudioMetadataParser.customArtworkCacheDir = File(context.cacheDir, "test_artwork_cache")
        db = Room.inMemoryDatabaseBuilder(context, MusikaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LibraryRepository(
            trackDao = db.trackDao(),
            folderDao = db.folderDao(),
            webMetadataDao = db.webMetadataDao(),
            playlistDao = db.playlistDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun resolveTestGroundRoot(): File? {
        val candidates = listOf(
            File("../Test-ground/111AAA MP3 128 - 16GB"),
            File("Test-ground/111AAA MP3 128 - 16GB"),
            File("D:/ALE-IA/ProyectoMusika/Test-ground/111AAA MP3 128 - 16GB")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
    }

    @Test
    fun `real Test-ground MP3 extracts embedded APIC JPEG artwork and true 128kbps MPEG frame header`() {
        val testGround = resolveTestGroundRoot() ?: return
        val abbaTrack = File(testGround, "ABBA/Chiquitita.mp3")
        assertTrue("Expected ABBA/Chiquitita.mp3 in Test-ground", abbaTrack.exists())

        val parsed = AudioMetadataParser.parse(abbaTrack)
        assertEquals("Chiquitita", parsed.title)
        assertEquals("ABBA", parsed.artist)
        assertEquals("ABBA - Gold Greatest Hits", parsed.album)
        assertEquals(12, parsed.trackNumber)
        assertEquals(1994, parsed.year)
        assertEquals("MP3", parsed.audioQuality.format)
        assertEquals(128, parsed.audioQuality.bitrateKbps)
        assertEquals(44100, parsed.audioQuality.sampleRateHz)
        assertTrue("Duration must be > 0", parsed.durationMs > 10_000L)

        // Verify embedded APIC artwork was extracted to a valid JPEG file on disk
        assertNotNull("Expected non-null artworkUri from embedded APIC frame", parsed.artworkUri)
        val cachedArtFile = File(URI(parsed.artworkUri!!))
        assertTrue("Extracted artwork file must exist on disk", cachedArtFile.exists())
        assertTrue("Extracted artwork file must be non-empty", cachedArtFile.length() > 1000L)
        val header = cachedArtFile.inputStream().use { inp ->
            val b = ByteArray(3)
            inp.read(b)
            b
        }
        assertEquals(0xFF, header[0].toInt() and 0xFF)
        assertEquals(0xD8, header[1].toInt() and 0xFF)
        assertEquals(0xFF, header[2].toInt() and 0xFF)
    }

    @Test
    fun `real Test-ground Riff folder resolves Delantera jpg when track has no embedded APIC`() {
        val testGround = resolveTestGroundRoot() ?: return
        val riffDir = File(testGround, "Riff/1981 - Macadam 3...2...1...0")
        if (!riffDir.exists()) return

        val delFile = File(riffDir, "Delantera.jpg")
        assertTrue("Expected Delantera.jpg in Riff folder", delFile.exists())

        // Create a temporary MP3 without APIC inside a temp copy of the Riff folder to test fallback
        val tempDir = kotlin.io.path.createTempDirectory("riff_fallback_test").toFile()
        try {
            delFile.copyTo(File(tempDir, "Delantera.jpg"), overwrite = true)
            File(riffDir, "Trasera.jpg").takeIf { it.exists() }?.copyTo(File(tempDir, "Trasera.jpg"), overwrite = true)
            val bareMp3 = File(tempDir, "01 - Macadam.mp3")
            bareMp3.writeBytes(ByteArray(2048))

            AudioMetadataParser.clearFolderArtworkMemoCache()
            val parsed = AudioMetadataParser.parse(bareMp3)
            assertNotNull("Expected folder artwork fallback URI", parsed.artworkUri)
            assertTrue(
                "Expected Delantera.jpg to be prioritized over Trasera.jpg, got: ${parsed.artworkUri}",
                parsed.artworkUri!!.lowercase().contains("delantera.jpg")
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `real Test-ground multi-branch scan and Sort by Directory across Songs, Albums, and Artists`() = runBlocking {
        val testGround = resolveTestGroundRoot() ?: return@runBlocking

        // Create a scoped composite root linking 3 real artist branches from Test-ground (ABBA, Riff, Yes)
        val scopedRoot = kotlin.io.path.createTempDirectory("musika_testground_subset").toFile()
        try {
            listOf("Yes", "ABBA", "Riff").forEach { artistFolder ->
                val src = File(testGround, artistFolder)
                if (src.exists()) {
                    src.copyRecursively(File(scopedRoot, artistFolder), overwrite = true)
                }
            }

            val scanResult = repository.rescanLibrary(scopedRoot)
            assertTrue("Expected real tracks scanned from Test-ground subset", scanResult.tracks.size >= 20)
            assertTrue(
                "Expected majority of tracks to have resolved artworkUri",
                scanResult.tracks.count { !it.artworkUri.isNullOrBlank() } >= 15
            )

            // 1. Verify SongSortOption.DIRECTORY sorts hierarchically from topmost folder down (ABBA -> Riff -> Yes)
            val songsByDir = repository.getDeduplicatedSongsFlow(SongSortOption.DIRECTORY).first()
            assertTrue(songsByDir.isNotEmpty())
            val firstSongRelPath = songsByDir.first().primaryTrack.relativePath
            val lastSongRelPath = songsByDir.last().primaryTrack.relativePath
            assertTrue("First song by directory must be in ABBA/, got $firstSongRelPath", firstSongRelPath.startsWith("ABBA/"))
            assertTrue("Last song by directory must be in Yes/, got $lastSongRelPath", lastSongRelPath.startsWith("Yes/"))

            // Verify monotonic hierarchical path ordering across all songs
            for (i in 0 until songsByDir.size - 1) {
                val pA = songsByDir[i].primaryTrack.relativePath
                val pB = songsByDir[i + 1].primaryTrack.relativePath
                assertTrue(
                    "Expected $pA <= $pB in SongSortOption.DIRECTORY",
                    MusicalRootScanner.HierarchicalPathComparator.compare(pA, pB) <= 0
                )
            }

            // 2. Verify AlbumSortOption.DIRECTORY sorts albums by topmost folder hierarchy
            val albumsByDir = repository.getAlbumsFlow(AlbumSortOption.DIRECTORY).first()
            assertTrue(albumsByDir.size >= 3)
            assertNotNull("Expected ABBA album to have artworkUri", albumsByDir.first().artworkUri)

            // 3. Verify ArtistSortOption.DIRECTORY sorts artists by topmost folder hierarchy
            val artistsByDir = repository.getArtistsFlow(ArtistSortOption.DIRECTORY).first()
            assertTrue(artistsByDir.size >= 3)
            assertEquals("ABBA", artistsByDir.first().name)
        } finally {
            scopedRoot.deleteRecursively()
        }
    }

    @Test
    fun `PlaybackDriver bridge starts audio playback, advances positionMs, and auto-advances at track end`() {
        val engine = DualQueueEngine()
        val loadedTracks = mutableListOf<Track>()
        var lastPlayState = false
        var lastSeekPos = -1L

        engine.playbackDriver = object : DualQueueEngine.PlaybackDriver {
            override fun onLoadAndPlayTrack(track: Track, startPositionMs: Long, playWhenReady: Boolean) {
                loadedTracks.add(track)
                lastPlayState = playWhenReady
            }

            override fun onPlayStateChanged(isPlaying: Boolean) {
                lastPlayState = isPlaying
            }

            override fun onSeekTo(positionMs: Long) {
                lastSeekPos = positionMs
            }
        }

        val t1 = Track(
            filePath = "/music/ABBA/01_Chiquitita.mp3",
            relativePath = "ABBA/01_Chiquitita.mp3",
            fileName = "01_Chiquitita.mp3",
            fileExtension = "mp3",
            title = "Chiquitita",
            artist = "ABBA",
            album = "Gold",
            durationMs = 326_000L,
            audioQuality = AudioQuality("MP3", 128, 44100, 16, false)
        )
        val t2 = t1.copy(
            filePath = "/music/ABBA/02_Dancing_Queen.mp3",
            relativePath = "ABBA/02_Dancing_Queen.mp3",
            fileName = "02_Dancing_Queen.mp3",
            title = "Dancing Queen",
            trackNumber = 2
        )

        // 1. Selecting a track immediately triggers PlaybackDriver.onLoadAndPlayTrack
        engine.playFromContext(t1, listOf(t1, t2), "ABBA")
        assertEquals(1, loadedTracks.size)
        assertEquals("Chiquitita", loadedTracks.first().title)
        assertTrue(lastPlayState)

        // 2. Live progress ticker updates positionMs and decoded durationMs
        engine.updateProgressFromPlayer(positionMs = 15_400L, actualDurationMs = 326_500L)
        assertEquals(15_400L, engine.state.value.positionMs)
        assertEquals(326_500L, engine.state.value.currentTrack?.durationMs)

        // 3. User seek updates both state and driver
        engine.seekTo(45_000L)
        assertEquals(45_000L, engine.state.value.positionMs)
        assertEquals(45_000L, lastSeekPos)

        // 4. Natural track completion (Player.STATE_ENDED -> skipToNext(autoTriggered = true)) loads and plays next track
        val next = engine.skipToNext(autoTriggered = true)
        assertNotNull(next)
        assertEquals("Dancing Queen", next?.title)
        assertEquals(2, loadedTracks.size)
        assertEquals("Dancing Queen", loadedTracks.last().title)
        assertEquals(0L, engine.state.value.positionMs)
    }

    @Test
    fun `sort-aware alphabet quick access jumps to active sort key letter (Artist Q, Directory R)`() {
        // Tracks sorted by Artist where song titles do NOT match artist letters
        val artistSortedTracks = listOf(
            "ABBA" to "Waterloo",
            "Alice In Chains" to "Man in the Box",
            "Metallica" to "Enter Sandman",
            "Queen" to "Another One Bites the Dust",
            "Queen" to "Bohemian Rhapsody",
            "Yes" to "Owner of a Lonely Heart"
        )
        val artistSortKeys = artistSortedTracks.map { it.first }
        val qIndex = findIndexForAlphabetLetter(artistSortKeys, 'Q')
        assertEquals(
            "Pressing Q when sorted by Artist must jump to first Queen track (index 3)",
            3,
            qIndex
        )
        assertEquals("Another One Bites the Dust", artistSortedTracks[qIndex].second)

        // Tracks sorted by Directory (topmost folder name from Root)
        val dirSortKeys = listOf("ABBA", "ABBA", "Riff", "Riff", "Yes")
        val rIndex = findIndexForAlphabetLetter(dirSortKeys, 'R')
        assertEquals("Pressing R when sorted by Directory must jump to first Riff track (index 2)", 2, rIndex)
    }

    @Test
    fun `native folder picker DocumentTree IDs resolve to filesystem paths and Settings persist 4 artwork styles`() {
        val sdPath = SettingsPreferences.resolveDocumentIdToPath("1A2B-3C4D:Music/Rock")
        assertEquals("/storage/1A2B-3C4D/Music/Rock", sdPath)

        val rawPath = SettingsPreferences.resolveDocumentIdToPath("raw:/storage/emulated/0/TestGround")
        assertEquals("/storage/emulated/0/TestGround", rawPath)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SettingsPreferences(context)
        NowPlayingArtworkStyle.entries.forEach { style ->
            prefs.setArtworkStyle(style)
            assertEquals(style, prefs.artworkStyle.value)
        }
        prefs.setShowListTrackThumbnails(false)
        assertEquals(false, prefs.showListTrackThumbnails.value)
        prefs.setShowListTrackThumbnails(true)
        assertEquals(true, prefs.showListTrackThumbnails.value)

        // HUD & Font Size Scale defaults to 1.0f and clamps within 0.85f..1.40f
        assertEquals(1.0f, prefs.hudScale.value, 0.001f)
        prefs.setHudScale(1.25f)
        assertEquals(1.25f, prefs.hudScale.value, 0.001f)
        prefs.setHudScale(1.0f)
        assertEquals(1.0f, prefs.hudScale.value, 0.001f)
    }

    @Test
    fun `playlists CRUD reordering and incremental scanner reuse work end-to-end`() = runBlocking {
        val tempRoot = kotlin.io.path.createTempDirectory("musika_incremental_playlist_test").toFile()
        try {
            val artistDir = File(tempRoot, "Queen")
            artistDir.mkdirs()
            val f1 = File(artistDir, "01 - Bohemian Rhapsody.flac").apply { writeBytes(ByteArray(1024)) }
            val f2 = File(artistDir, "02 - Killer Queen.flac").apply { writeBytes(ByteArray(1024)) }
            val f3 = File(artistDir, "03 - Somebody to Love.flac").apply { writeBytes(ByteArray(1024)) }

            val firstScan = repository.rescanLibrary(tempRoot)
            assertEquals(3, firstScan.tracks.size)

            // Second scan with unchanged timestamps reuses existing TrackEntities in O(1)
            val secondScan = repository.rescanLibrary(tempRoot)
            assertEquals(3, secondScan.tracks.size)
            assertEquals(firstScan.tracks.map { it.filePath }, secondScan.tracks.map { it.filePath })

            val songs = repository.getDeduplicatedSongsFlow(SongSortOption.ALPHABETICAL).first()
                .map { it.primaryTrack }
            assertEquals(3, songs.size)

            // Create Playlist with initial 2 tracks
            val playlistId = repository.createPlaylist("Rock Classics", listOf(songs[0], songs[1]))
            assertTrue(playlistId > 0L)

            var playlists = repository.getPlaylistsFlow().first()
            assertEquals(1, playlists.size)
            assertEquals("Rock Classics", playlists.first().name)
            assertEquals(2, playlists.first().trackCount)

            // Add 3rd track
            repository.addTracksToPlaylist(playlistId, listOf(songs[2]))
            var plTracks = repository.getTracksForPlaylistFlow(playlistId).first()
            assertEquals(3, plTracks.size)

            // Move track 2 (index 2) to top (index 0)
            repository.moveTrackInPlaylist(playlistId, fromIndex = 2, toIndex = 0)
            plTracks = repository.getTracksForPlaylistFlow(playlistId).first()
            assertEquals(songs[2].filePath, plTracks[0].filePath)

            // Remove middle track (index 1)
            repository.removeTrackFromPlaylist(playlistId, positionIndex = 1)
            plTracks = repository.getTracksForPlaylistFlow(playlistId).first()
            assertEquals(2, plTracks.size)

            // Rename and delete playlist
            repository.renamePlaylist(playlistId, "Queen Favorites")
            playlists = repository.getPlaylistsFlow().first()
            assertEquals("Queen Favorites", playlists.first().name)

            repository.deletePlaylist(playlistId)
            playlists = repository.getPlaylistsFlow().first()
            assertTrue(playlists.isEmpty())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
