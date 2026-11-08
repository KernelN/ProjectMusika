package com.musika.player.data.scanner

import com.musika.player.core.model.AudioQuality
import com.musika.player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatDeduplicationAndQualityTest {

    @Test
    fun `audioQuality ranks Lossless FLAC ALAC WAV above Opus OGG AAC above MP3 by bitrate`() {
        val flac24 = AudioQuality("FLAC", bitrateKbps = 1400, sampleRateHz = 96000, bitDepth = 24, isLossless = true)
        val flac16 = AudioQuality("FLAC", bitrateKbps = 900, sampleRateHz = 44100, bitDepth = 16, isLossless = true)
        val wav16 = AudioQuality("WAV", bitrateKbps = 1411, sampleRateHz = 44100, bitDepth = 16, isLossless = true)
        val opus160 = AudioQuality("OPUS", bitrateKbps = 160, sampleRateHz = 48000, bitDepth = 16, isLossless = false)
        val aac256 = AudioQuality("AAC", bitrateKbps = 256, sampleRateHz = 44100, bitDepth = 16, isLossless = false)
        val mp3_320 = AudioQuality("MP3", bitrateKbps = 320, sampleRateHz = 44100, bitDepth = 16, isLossless = false)
        val mp3_192 = AudioQuality("MP3", bitrateKbps = 192, sampleRateHz = 44100, bitDepth = 16, isLossless = false)
        val mp3_128 = AudioQuality("MP3", bitrateKbps = 128, sampleRateHz = 44100, bitDepth = 16, isLossless = false)

        assertTrue("24-bit FLAC > 16-bit FLAC", flac24 > flac16)
        assertTrue("Lossless FLAC > Lossy AAC", flac16 > aac256)
        assertTrue("Lossless WAV > Lossy OPUS", wav16 > opus160)
        assertTrue("Tier 2 OPUS > Tier 1 MP3 320", opus160 > mp3_320)
        assertTrue("Tier 2 AAC 256 > Tier 2 OPUS 160", aac256 > opus160)
        assertTrue("MP3 320 > MP3 192", mp3_320 > mp3_192)
        assertTrue("MP3 192 > MP3 128", mp3_192 > mp3_128)
    }

    @Test
    fun `deduplicator merges duplicate extensions by Artist Album Title and selects highest quality with badge`() {
        val mp3Track = Track(
            filePath = "/music/daft_punk/discovery/01_one_more_time.mp3",
            relativePath = "daft_punk/discovery/01_one_more_time.mp3",
            fileName = "01_one_more_time.mp3",
            fileExtension = "mp3",
            title = "One More Time",
            artist = "Daft Punk",
            album = "Discovery",
            audioQuality = AudioQuality("MP3", bitrateKbps = 320, isLossless = false)
        )
        val flacTrack = Track(
            filePath = "/music/daft_punk/discovery/01_one_more_time.flac",
            relativePath = "daft_punk/discovery/01_one_more_time.flac",
            fileName = "01_one_more_time.flac",
            fileExtension = "flac",
            title = "One More Time",
            artist = "Daft Punk",
            album = "Discovery",
            audioQuality = AudioQuality("FLAC", bitrateKbps = 950, isLossless = true)
        )

        val groups = FormatDeduplicator.deduplicateTracks(listOf(mp3Track, flacTrack))
        assertEquals(1, groups.size)

        val group = groups.first()
        assertEquals("FLAC must be chosen as primary highest-quality track", "flac", group.primaryTrack.fileExtension)
        assertEquals(1, group.primaryTrack.alternateVariantsCount)
        assertEquals("FLAC +1", group.primaryTrack.formatBadgeText)
        assertEquals(2, group.allVariants.size)

        // Verify manual alternate variant selection
        val selectedMp3 = FormatDeduplicator.selectVariant(group, mp3Track.filePath)
        assertEquals(mp3Track.filePath, selectedMp3.filePath)

        // Verify Directory View NEVER deduplicates
        val rawDirTracks = FormatDeduplicator.rawTracksForDirectoryView(listOf(mp3Track, flacTrack))
        assertEquals(2, rawDirTracks.size)
        assertFalse(rawDirTracks.any { it.alternateVariantsCount > 0 })
    }
}
