package com.viswa2k.syncpad.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.viswa2k.syncpad.data.entity.SyncMetaEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for sync metadata operations.
 */
@Dao
interface SyncMetaDao {

    // ============================================
    // INSERT/UPDATE OPERATIONS
    // ============================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(syncMeta: SyncMetaEntity)

    // ============================================
    // QUERY OPERATIONS
    // ============================================

    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    fun getValueFlow(key: String): Flow<String?>

    @Query("SELECT * FROM sync_meta WHERE `key` = :key")
    suspend fun get(key: String): SyncMetaEntity?

    @Query("SELECT * FROM sync_meta")
    suspend fun getAll(): List<SyncMetaEntity>

    // ============================================
    // DELETE OPERATIONS
    // ============================================

    @Query("DELETE FROM sync_meta WHERE `key` = :key")
    suspend fun delete(key: String): Int

    @Query("DELETE FROM sync_meta")
    suspend fun deleteAll(): Int

    // ============================================
    // ATOMIC OPERATIONS
    // ============================================

    /**
     * Atomically clear sync progress tracking data.
     * Deletes sync_last_id and sync_total_expected, sets sync_in_progress to false.
     */
    @Transaction
    suspend fun clearSyncProgress() {
        delete(SyncMetaEntity.KEY_SYNC_LAST_ID)
        delete(SyncMetaEntity.KEY_SYNC_TOTAL_EXPECTED)
        upsert(SyncMetaEntity(key = SyncMetaEntity.KEY_SYNC_IN_PROGRESS, value = "false"))
    }
}
