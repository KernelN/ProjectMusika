package com.musika.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.musika.player.data.local.entity.WebMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WebMetadataDao {

    @Query("SELECT * FROM web_metadata WHERE trackFilePath = :filePath LIMIT 1")
    suspend fun getMetadataForTrack(filePath: String): WebMetadataEntity?

    @Query("SELECT * FROM web_metadata WHERE status = 'UNSAVED'")
    fun getUnsavedCandidateMetadata(): Flow<List<WebMetadataEntity>>

    @Query("SELECT * FROM web_metadata")
    fun getAllWebMetadataFlow(): Flow<List<WebMetadataEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetadata(metadata: WebMetadataEntity)

    @Update
    suspend fun updateMetadata(metadata: WebMetadataEntity)

    @Query("DELETE FROM web_metadata WHERE trackFilePath = :filePath")
    suspend fun deleteMetadata(filePath: String)

    @Query("DELETE FROM web_metadata")
    suspend fun clearAll()
}
