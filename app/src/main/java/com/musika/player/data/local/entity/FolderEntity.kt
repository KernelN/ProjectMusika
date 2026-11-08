package com.musika.player.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.musika.player.core.model.FolderNode

@Entity(
    tableName = "folders",
    indices = [
        Index(value = ["parentPath"]),
        Index(value = ["recursiveTrackCount"]),
        Index(value = ["folderName"])
    ]
)
data class FolderEntity(
    @PrimaryKey
    val absolutePath: String,
    val folderName: String,
    val parentPath: String?,
    val relativePathFromRoot: String,
    val directTrackCount: Int = 0,
    val recursiveTrackCount: Int = 0,
    val hasSubfolders: Boolean = false,
    val isDiscFolder: Boolean = false,
    val discNumber: Int? = null,
    val siblingDiscFoldersJson: String = "[]" // JSON serialized list
) {
    fun toDomain(siblings: List<String> = emptyList()): FolderNode = FolderNode(
        absolutePath = absolutePath,
        folderName = folderName,
        relativePathFromRoot = relativePathFromRoot,
        directTrackCount = directTrackCount,
        recursiveTrackCount = recursiveTrackCount,
        hasSubfolders = hasSubfolders,
        isDiscFolder = isDiscFolder,
        discNumber = discNumber,
        siblingDiscFolders = siblings
    )
}
