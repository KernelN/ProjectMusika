package com.musika.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.musika.player.data.local.entity.PlaylistEntity
import com.musika.player.data.local.entity.PlaylistEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    fun getAllPlaylistsFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_entries ORDER BY playlistId ASC, positionIndex ASC")
    fun getAllPlaylistEntriesFlow(): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY positionIndex ASC")
    fun getEntriesForPlaylistFlow(playlistId: Long): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY positionIndex ASC")
    suspend fun getEntriesForPlaylistSnapshot(playlistId: Long): List<PlaylistEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :newName WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, newName: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun clearEntriesForPlaylist(playlistId: Long)

    @Query("SELECT COALESCE(MAX(positionIndex), -1) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun getMaxPositionIndex(playlistId: Long): Int

    @Transaction
    suspend fun replaceEntriesForPlaylist(playlistId: Long, trackPaths: List<String>) {
        clearEntriesForPlaylist(playlistId)
        val newEntries = trackPaths.mapIndexed { idx, path ->
            PlaylistEntryEntity(
                playlistId = playlistId,
                trackFilePath = path,
                positionIndex = idx
            )
        }
        if (newEntries.isNotEmpty()) {
            insertEntries(newEntries)
        }
    }
}
