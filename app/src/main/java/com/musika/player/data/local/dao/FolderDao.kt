package com.musika.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.musika.player.data.local.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders WHERE parentPath = :parentPath ORDER BY folderName COLLATE NOCASE ASC")
    fun getSubfoldersAlphabetical(parentPath: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE parentPath = :parentPath ORDER BY recursiveTrackCount DESC, folderName COLLATE NOCASE ASC")
    fun getSubfoldersByMusicCount(parentPath: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE parentPath = :parentPath")
    fun getSubfoldersDefault(parentPath: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE absolutePath = :path LIMIT 1")
    suspend fun getFolderByPath(path: String): FolderEntity?

    @Query("SELECT * FROM folders")
    suspend fun getAllFolders(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<FolderEntity>)

    @Query("DELETE FROM folders")
    suspend fun clearAll()
}
