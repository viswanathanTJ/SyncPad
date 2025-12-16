package com.viswa2k.syncpad.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.viswa2k.syncpad.data.dao.BlogDao
import com.viswa2k.syncpad.data.dao.PrefixIndexDao
import com.viswa2k.syncpad.data.dao.SyncMetaDao
import com.viswa2k.syncpad.data.entity.BlogEntity
import com.viswa2k.syncpad.data.entity.PrefixIndexEntity
import com.viswa2k.syncpad.data.entity.SyncMetaEntity

/**
 * Room database for SyncPad application.
 * 
 * Tables:
 * - blogs: Main blog posts table
 * - prefix_index: LOCAL ONLY derived index for navigation
 * - sync_meta: Sync metadata key-value store
 */
@Database(
    entities = [
        BlogEntity::class,
        PrefixIndexEntity::class,
        SyncMetaEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blogDao(): BlogDao
    abstract fun prefixIndexDao(): PrefixIndexDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        const val DATABASE_NAME = "syncpad.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Get the singleton database instance.
         * Uses double-checked locking for thread safety.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
