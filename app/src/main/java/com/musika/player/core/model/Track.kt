package com.musika.player.core.model

import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
enum class SongSortOption {
    ALPHABETICAL,
    ALBUM,
    ARTIST,
    DIRECTORY
}

@Serializable
enum class AlbumSortOption {
    ALPHABETICAL,
    ARTIST,
    DIRECTORY
}

@Serializable
enum class ArtistSortOption {
    ALPHABETICAL,
    DIRECTORY
}

@Serializable
enum class NowPlayingArtworkStyle(val displayName: String) {
    ROUNDED_SQUARE("Classic Rounded Square"),
    SPINNING_DISC("Spinning Vinyl / CD Disc"),
    FULL_SCREEN_BACKGROUND("Full-Screen Background (Spotify Style)"),
    WINAMP_RETRO_BEZEL("Retro Winamp Bezel Frame")
}

@Serializable
data class Track(
    val id: Long = 0L,
    val filePath: String,
    val relativePath: String, // Path relative to Musical Root Folder (for Local-Match-First)
    val fileName: String,
    val fileExtension: String, // e.g. "flac", "mp3"
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String? = null,
    val discNumber: Int = 1,
    val totalDiscs: Int = 1,
    val trackNumber: Int = 1,
    val durationMs: Long = 0L,
    val audioQuality: AudioQuality,
    val dateAdded: Long = System.currentTimeMillis(),
    val year: Int? = null,
    val genre: String? = null,
    val parentFolderPath: String = "",
    val artworkUri: String? = null,
    val webMatchPending: Boolean = false,
    val alternateVariantsCount: Int = 0 // Used for UI format badge e.g. "FLAC +1"
) {
    /**
     * Normalized key used to deduplicate across formats: (Artist + Album + Title)
     */
    val normalizedKey: String
        get() = "${artist.trim().lowercase(Locale.US)}|${album.trim().lowercase(Locale.US)}|${title.trim().lowercase(Locale.US)}"

    /**
     * Display label for format badge
     */
    val formatBadgeText: String
        get() = if (alternateVariantsCount > 0) {
            "${audioQuality.format.uppercase(Locale.US)} +$alternateVariantsCount"
        } else {
            audioQuality.format.uppercase(Locale.US)
        }
}

/**
 * Deduplicated group containing the highest quality primary track and its alternates.
 */
data class DeduplicatedTrackGroup(
    val normalizedKey: String,
    val primaryTrack: Track,
    val allVariants: List<Track>
) {
    val count: Int get() = allVariants.size
}
