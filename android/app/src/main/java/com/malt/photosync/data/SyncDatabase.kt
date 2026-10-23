package com.malt.photosync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SyncRecord::class], version = 1, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun dao(): SyncRecordDao

    companion object {
        @Volatile
        private var instance: SyncDatabase? = null

        fun get(context: Context): SyncDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, SyncDatabase::class.java, "photosync.db"
                ).build().also { instance = it }
            }
    }
}
