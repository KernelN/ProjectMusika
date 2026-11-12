package com.musika.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.musika.player.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    fun getAllTracksAlphabetical(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY album COLLATE NOCASE ASC, discNumber ASC, trackNumber ASC")
    fun getAllTracksByAlbum(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY artist COLLATE NOCASE ASC, album COLLATE NOCASE ASC, trackNumber ASC")
    fun getAllTracksByArtist(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE parentFolderPath = :folderPath ORDER BY fileName COLLATE NOCASE ASC")
    fun getTracksInFolder(folderPath: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE album = :album ORDER BY discNumber ASC, trackNumber ASC")
    fun getTracksForAlbum(album: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY album COLLATE NOCASE ASC, trackNumber ASC")
    fun getTracksForArtist(artist: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE normalizedKey = :normalizedKey")
    suspend fun getTracksByNormalizedKey(normalizedKey: String): List<TrackEntity>

    @Query("SELECT * FROM tracks")
    suspend fun getAllTracksSnapshot(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE filePath = :filePath LIMIT 1")
    suspend fun getTrackByPath(filePath: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Update
    suspend fun updateTrack(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE filePath IN (:filePaths)")
    suspend fun deleteTracksByPaths(filePaths: List<String>)

    @Query("DELETE FROM tracks WHERE filePath NOT IN (:activeFilePaths)")
    suspend fun pruneDeletedTracks(activeFilePaths: List<String>)

    @Query("DELETE FROM tracks")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getTrackCount(): Int
}
