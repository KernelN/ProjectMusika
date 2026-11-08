package com.musika.player.data.scanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.musika.player.core.model.AudioQuality
import com.musika.player.core.model.FolderSortOption
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.Track
import com.musika.player.data.local.entity.WebMetadataEntity
import com.musika.player.data.metadata.ConfirmationScope
import com.musika.player.data.metadata.MetadataWriteDialogState
import com.musika.player.data.metadata.TrackMetadataDiff
import com.musika.player.data.metadata.WriteConfirmationStep
import com.musika.player.data.repository.SettingsPreferences
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerAndMultiDiscTest {

    @Test
    fun `settingsPreferences defaults Always Scan Entire Phone to OFF`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SettingsPreferences(context)
        assertFalse(
            "Always scan entire phone for music MUST be OFF by default",
            prefs.alwaysScanEntirePhone.value
        )
    }

    @Test
    fun `musicalRootScanner calculates recursive audio file sum across subfolder branches and sorts Most Music Files accurately`() {
        val rootDir = createTempDirectory("musika_test_root").toFile()
        try {
            // ArtistA has 0 direct files, but 2 albums with 3 + 2 = 5 nested audio files
            val artistA = File(rootDir, "ArtistA").apply { mkdirs() }
            val albumA1 = File(artistA, "Album1").apply { mkdirs() }
            val albumA2 = File(artistA, "Album2").apply { mkdirs() }
            File(albumA1, "01_song.flac").writeBytes(ByteArray(2048))
            File(albumA1, "02_song.flac").writeBytes(ByteArray(2048))
            File(albumA1, "03_song.flac").writeBytes(ByteArray(2048))
            File(albumA2, "01_song.mp3").writeBytes(ByteArray(2048))
            File(albumA2, "02_song.mp3").writeBytes(ByteArray(2048))

            // ArtistB has 2 direct files, 0 nested subfolders (total = 2)
            val artistB = File(rootDir, "ArtistB").apply { mkdirs() }
            File(artistB, "01_direct.mp3").writeBytes(ByteArray(2048))
            File(artistB, "02_direct.mp3").writeBytes(ByteArray(2048))

            val scanResult = MusicalRootScanner.scan(rootDir)
            assertEquals(7, scanResult.tracks.size)

            val rootNorm = MusicalRootScanner.normalizePath(rootDir.absolutePath)
            val topLevelFolders = scanResult.folders
                .filter { it.parentPath == rootNorm }
                .map { it.toDomain() }

            assertEquals(2, topLevelFolders.size)
            val nodeA = topLevelFolders.first { it.folderName == "ArtistA" }
            val nodeB = topLevelFolders.first { it.folderName == "ArtistB" }

            assertEquals(0, nodeA.directTrackCount)
            assertEquals("ArtistA recursive count must sum nested Album1(3) + Album2(2) = 5", 5, nodeA.recursiveTrackCount)
            assertEquals(2, nodeB.directTrackCount)
            assertEquals(2, nodeB.recursiveTrackCount)

            val sortedByMostFiles = MusicalRootScanner.sortFolders(
                listOf(nodeB, nodeA),
                FolderSortOption.MOST_MUSIC_FILES
            )
            assertEquals("ArtistA (5 recursive files) must sort ahead of ArtistB (2 direct files)", "ArtistA", sortedByMostFiles.first().folderName)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `multiDiscDetector recognizes CD1 CD2 and Disc folders and orders sequentially or randomized`() {
        assertEquals(1, MultiDiscDetector.extractDiscNumberFromFolderName("CD1"))
        assertEquals(2, MultiDiscDetector.extractDiscNumberFromFolderName("CD 02"))
        assertEquals(1, MultiDiscDetector.extractDiscNumberFromFolderName("Disc 1"))
        assertEquals(2, MultiDiscDetector.extractDiscNumberFromFolderName("Disc 2 - Live"))

        val siblings = MultiDiscDetector.findSiblingDiscFolders(
            listOf("/root/TheWall/CD1", "/root/TheWall/CD2"),
            mapOf("/root/TheWall/CD1" to "CD1", "/root/TheWall/CD2" to "CD2")
        )
        assertEquals(listOf("/root/TheWall/CD2"), siblings["/root/TheWall/CD1"])
        assertEquals(listOf("/root/TheWall/CD1"), siblings["/root/TheWall/CD2"])

        val d2t1 = sampleTrack("d2t1", disc = 2, track = 1)
        val d1t2 = sampleTrack("d1t2", disc = 1, track = 2)
        val d1t1 = sampleTrack("d1t1", disc = 1, track = 1)
        val d2t2 = sampleTrack("d2t2", disc = 2, track = 2)

        val sequential = MultiDiscDetector.orderTracksForPlayAllDiscs(
            listOf(d2t1, d1t2, d1t1, d2t2),
            RandomMode.OFF
        )
        assertEquals(
            listOf("d1t1", "d1t2", "d2t1", "d2t2"),
            sequential.map { it.title }
        )

        val randomized = MultiDiscDetector.orderTracksForPlayAllDiscs(
            listOf(d1t1, d1t2, d2t1, d2t2),
            RandomMode.SHUFFLE_POOL,
            Random(42)
        )
        assertEquals(4, randomized.size)
        assertTrue(randomized.map { it.title }.containsAll(listOf("d1t1", "d1t2", "d2t1", "d2t2")))
    }

    @Test
    fun `multiStepWriteConfirmation enforces 3 explicit steps before authorizing local file write`() {
        val track = sampleTrack("Track 1", disc = 1, track = 1)
        val candidate = WebMetadataEntity(
            trackFilePath = track.filePath,
            candidateTitle = "Remastered Track 1",
            candidateArtist = "Verified Artist",
            candidateAlbum = "Verified Album",
            candidateYear = 2025,
            candidateTrackNumber = 1,
            candidateDiscNumber = 1,
            musicBrainzId = "mbid-1",
            acoustId = "acoust-1",
            status = WebMetadataEntity.STATUS_UNSAVED
        )
        val diff = TrackMetadataDiff.from(track, candidate)
        assertTrue(diff.hasChanges)

        var state = MetadataWriteDialogState(
            scope = ConfirmationScope.PER_TRACK,
            targetLabel = track.title,
            diffs = listOf(diff)
        )
        assertEquals(WriteConfirmationStep.STEP_1_REVIEW_DIFF, state.currentStep)
        assertFalse("Must NOT authorize write at Step 1", state.isWriteAuthorized)

        state = state.confirmCurrentStep()
        assertEquals(WriteConfirmationStep.STEP_2_FIRST_ARE_YOU_SURE, state.currentStep)
        assertFalse("Must NOT authorize write at Step 2", state.isWriteAuthorized)

        state = state.confirmCurrentStep()
        assertEquals(WriteConfirmationStep.STEP_3_FINAL_ARE_YOU_SURE, state.currentStep)
        assertFalse("Must NOT authorize write before confirming Step 3", state.isWriteAuthorized)

        state = state.confirmCurrentStep()
        assertEquals(WriteConfirmationStep.COMPLETED_READY_TO_WRITE, state.currentStep)
        assertTrue("Write is authorized ONLY after completing all 3 confirmation steps", state.isWriteAuthorized)
    }

    private fun sampleTrack(title: String, disc: Int, track: Int): Track = Track(
        filePath = "/music/$title.flac",
        relativePath = "$title.flac",
        fileName = "$title.flac",
        fileExtension = "flac",
        title = title,
        artist = "Artist",
        album = "Album",
        discNumber = disc,
        totalDiscs = 2,
        trackNumber = track,
        durationMs = 180_000L,
        audioQuality = AudioQuality("FLAC", 900, 44100, 16, true)
    )
}
