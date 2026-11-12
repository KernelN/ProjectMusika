package com.musika.player.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Playlist(
    val id: Long = 0L,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val trackCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val artworkUri: String? = null,
    val trackFilePaths: List<String> = emptyList()
)
