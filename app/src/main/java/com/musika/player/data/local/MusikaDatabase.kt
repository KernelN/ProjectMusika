package com.musika.player.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.musika.player.data.local.dao.FolderDao
import com.musika.player.data.local.dao.TrackDao
import com.musika.player.data.local.dao.WebMetadataDao
import com.musika.player.data.local.entity.FolderEntity
import com.musika.player.data.local.entity.TrackEntity
import com.musika.player.data.local.entity.WebMetadataEntity

@Database(
    entities = [
        TrackEntity::class,
        FolderEntity::class,
        WebMetadataEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class MusikaDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun folderDao(): FolderDao
    abstract fun webMetadataDao(): WebMetadataDao

    companion object {
        @Volatile
        private var INSTANCE: MusikaDatabase? = null

        fun getDatabase(context: Context): MusikaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusikaDatabase::class.java,
                    "musika_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
