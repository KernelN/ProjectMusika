package com.musika.player.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "web_metadata")
data class WebMetadataEntity(
    @PrimaryKey
    val trackFilePath: String,
    val candidateTitle: String?,
    val candidateArtist: String?,
    val candidateAlbum: String?,
    val candidateYear: Int?,
    val candidateTrackNumber: Int?,
    val candidateDiscNumber: Int?,
    val musicBrainzId: String?,
    val acoustId: String?,
    val status: String = STATUS_UNSAVED, // "UNSAVED", "APPROVED", "WRITTEN", "REJECTED"
    val fetchedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_UNSAVED = "UNSAVED"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_WRITTEN = "WRITTEN"
        const val STATUS_REJECTED = "REJECTED"
    }
}
