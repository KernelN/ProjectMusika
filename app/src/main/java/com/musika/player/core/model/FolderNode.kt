package com.musika.player.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class FolderSortOption {
    SYSTEM_DEFAULT,
    ALPHABETICAL,
    MOST_MUSIC_FILES // MUST sort by recursiveTrackCount
}

@Serializable
data class FolderNode(
    val absolutePath: String,
    val folderName: String,
    val relativePathFromRoot: String,
    val directTrackCount: Int = 0,
    val recursiveTrackCount: Int = 0, // Recursive sum of audio files across entire subfolder branch
    val hasSubfolders: Boolean = false,
    val isDiscFolder: Boolean = false,
    val discNumber: Int? = null,
    val siblingDiscFolders: List<String> = emptyList()
)
