package com.musika.player.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Album(
    val title: String,
    val artist: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val discCount: Int = 1,
    val discFolders: List<String> = emptyList(),
    val folderPaths: List<String> = emptyList(),
    val year: Int? = null,
    val artworkUri: String? = null
) {
    val isMultiDisc: Boolean
        get() = discCount > 1 || discFolders.size > 1
}
