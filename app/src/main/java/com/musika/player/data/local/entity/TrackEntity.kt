package com.musika.player.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.musika.player.core.model.AudioQuality
import com.musika.player.core.model.Track

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["normalizedKey"]),
        Index(value = ["parentFolderPath"]),
        Index(value = ["album"]),
        Index(value = ["artist"]),
        Index(value = ["title"])
    ]
)
data class TrackEntity(
    @PrimaryKey
    val filePath: String,
    val relativePath: String,
    val fileName: String,
    val fileExtension: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String? = null,
    val discNumber: Int = 1,
    val totalDiscs: Int = 1,
    val trackNumber: Int = 1,
    val durationMs: Long = 0L,
    val format: String = "UNKNOWN",
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 44100,
    val bitDepth: Int = 16,
    val isLossless: Boolean = false,
    val parentFolderPath: String = "",
    val dateAdded: Long = System.currentTimeMillis(),
    val year: Int? = null,
    val genre: String? = null,
    val normalizedKey: String, // lowercase: artist|album|title
    val webMatchPending: Boolean = false
) {
    fun toDomainModel(alternateVariantsCount: Int = 0): Track = Track(
        filePath = filePath,
        relativePath = relativePath,
        fileName = fileName,
        fileExtension = fileExtension,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        discNumber = discNumber,
        totalDiscs = totalDiscs,
        trackNumber = trackNumber,
        durationMs = durationMs,
        audioQuality = AudioQuality(
            format = format,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            isLossless = isLossless
        ),
        dateAdded = dateAdded,
        year = year,
        genre = genre,
        parentFolderPath = parentFolderPath,
        webMatchPending = webMatchPending,
        alternateVariantsCount = alternateVariantsCount
    )

    companion object {
        fun fromDomain(track: Track): TrackEntity = TrackEntity(
            filePath = track.filePath,
            relativePath = track.relativePath,
            fileName = track.fileName,
            fileExtension = track.fileExtension,
            title = track.title,
            artist = track.artist,
            album = track.album,
            albumArtist = track.albumArtist,
            discNumber = track.discNumber,
            totalDiscs = track.totalDiscs,
            trackNumber = track.trackNumber,
            durationMs = track.durationMs,
            format = track.audioQuality.format,
            bitrateKbps = track.audioQuality.bitrateKbps,
            sampleRateHz = track.audioQuality.sampleRateHz,
            bitDepth = track.audioQuality.bitDepth,
            isLossless = track.audioQuality.isLossless,
            parentFolderPath = track.parentFolderPath,
            dateAdded = track.dateAdded,
            year = track.year,
            genre = track.genre,
            normalizedKey = track.normalizedKey,
            webMatchPending = track.webMatchPending
        )
    }
}
