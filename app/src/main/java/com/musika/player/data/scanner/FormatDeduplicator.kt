package com.musika.player.data.scanner

import com.musika.player.core.model.DeduplicatedTrackGroup
import com.musika.player.core.model.Track

/**
 * Smart Format Deduplicator (Specification #6).
 * - Merges duplicate songs with different file extensions (e.g., .flac + .mp3) sharing the same
 *   (Artist + Album + Title) into a single selectable row in Songs, Albums, and Artists views.
 * - NEVER deduplicates in Directory View (Folders), which always shows raw files.
 * - Playing a merged song automatically selects the highest quality file:
 *   Lossless FLAC/ALAC/WAV > Opus/OGG/AAC > MP3 by bitrate, with a format badge (e.g. "FLAC +1")
 *   to pick an alternate extension manually.
 */
object FormatDeduplicator {

    /**
     * Groups tracks by normalized (Artist + Album + Title) key, sorting variants in descending
     * order of AudioQuality so the highest quality variant is always selected as primaryTrack.
     */
    fun deduplicateTracks(rawTracks: List<Track>): List<DeduplicatedTrackGroup> {
        if (rawTracks.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<Track>>()
        for (track in rawTracks) {
            grouped.getOrPut(track.normalizedKey) { mutableListOf() }.add(track)
        }

        return grouped.entries.map { (normalizedKey, variants) ->
            // Sort highest quality first using AudioQuality.compareTo
            val sortedVariants = variants.sortedWith(
                Comparator<Track> { a, b -> b.audioQuality.compareTo(a.audioQuality) }
                    .thenBy { it.filePath }
            )
            val alternateCount = (sortedVariants.size - 1).coerceAtLeast(0)
            val primaryWithBadge = sortedVariants.first().copy(
                alternateVariantsCount = alternateCount
            )
            val updatedVariants = sortedVariants.mapIndexed { index, variant ->
                if (index == 0) primaryWithBadge else variant.copy(alternateVariantsCount = alternateCount)
            }
            DeduplicatedTrackGroup(
                normalizedKey = normalizedKey,
                primaryTrack = primaryWithBadge,
                allVariants = updatedVariants
            )
        }
    }

    /**
     * Convenience helper returning the primary (highest quality) Track for each deduplicated group.
     */
    fun deduplicateToPrimaryTracks(rawTracks: List<Track>): List<Track> {
        return deduplicateTracks(rawTracks).map { it.primaryTrack }
    }

    /**
     * Directory View (Folders) MUST NEVER deduplicate — always returns raw files with 0 badge count.
     */
    fun rawTracksForDirectoryView(rawTracks: List<Track>): List<Track> {
        return rawTracks.map { it.copy(alternateVariantsCount = 0) }
    }

    /**
     * Selects a specific file extension/path variant from a deduplicated group when the user taps
     * the format badge (e.g. "FLAC +1") to manually pick an alternate extension.
     */
    fun selectVariant(group: DeduplicatedTrackGroup, targetFilePath: String): Track {
        return group.allVariants.firstOrNull { it.filePath == targetFilePath } ?: group.primaryTrack
    }
}
