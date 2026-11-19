package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DownloadEntity::class, BookmarkEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class StreamDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: StreamDatabase? = null

        fun getInstance(context: Context): StreamDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StreamDatabase::class.java,
                    "streamvault.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
