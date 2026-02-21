package com.viswa2k.syncpad.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blogDao(): BlogDao
    abstract fun prefixIndexDao(): PrefixIndexDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        const val DATABASE_NAME = "syncpad.db"

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_blogs_is_deleted_title_prefix_title_id` " +
                    "ON `blogs` (`is_deleted`, `title_prefix`, `title`, `id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_blogs_is_deleted_updated_at_id` " +
                    "ON `blogs` (`is_deleted`, `updated_at`, `id`)"
                )
            }
        }
    }
}
