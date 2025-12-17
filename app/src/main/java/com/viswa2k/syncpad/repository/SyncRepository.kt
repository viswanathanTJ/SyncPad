package com.viswa2k.syncpad.repository

import com.viswa2k.syncpad.data.dao.SyncMetaDao
import com.viswa2k.syncpad.data.entity.SyncMetaEntity
import com.viswa2k.syncpad.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for sync metadata operations.
 * Stores sync-related key-value pairs like last_sync_time.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val syncMetaDao: SyncMetaDao
) {
    companion object {
        private const val TAG = "SyncRepository"
    }

    // ============================================
    // LAST SYNC TIME
    // ============================================

    /**
     * Get the last sync time as Unix timestamp in milliseconds.
     * Returns 0 if no sync has occurred.
     */
    suspend fun getLastSyncTime(): Result<Long> {
        return withContext(Dispatchers.IO) {
            try {
                val value = syncMetaDao.getValue(SyncMetaEntity.KEY_LAST_SYNC_TIME)
                val time = value?.toLongOrNull() ?: 0L
                Result.success(time)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting last sync time", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get the last sync time as a Flow for reactive updates.
     */
    fun getLastSyncTimeFlow(): Flow<Long> {
        return syncMetaDao.getValueFlow(SyncMetaEntity.KEY_LAST_SYNC_TIME)
            .map { it?.toLongOrNull() ?: 0L }
            .flowOn(Dispatchers.IO)
            .catch { e ->
                AppLogger.e(TAG, "Error in last sync time flow", e)
                throw e
            }
    }

    /**
     * Set the last sync time.
     */
    suspend fun setLastSyncTime(timestamp: Long): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                syncMetaDao.upsert(
                    SyncMetaEntity(
                        key = SyncMetaEntity.KEY_LAST_SYNC_TIME,
                        value = timestamp.toString()
                    )
                )
                AppLogger.i(TAG, "Set last sync time to: $timestamp")
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting last sync time", e)
                Result.failure(e)
            }
        }
    }

    // ============================================
    // DEVICE ID
    // ============================================

    /**
     * Get the device ID.
     * Returns null if not set.
     */
    suspend fun getDeviceId(): Result<String?> {
        return withContext(Dispatchers.IO) {
            try {
                val value = syncMetaDao.getValue(SyncMetaEntity.KEY_DEVICE_ID)
                Result.success(value)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting device id", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Set the device ID.
     */
    suspend fun setDeviceId(deviceId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                syncMetaDao.upsert(
                    SyncMetaEntity(
                        key = SyncMetaEntity.KEY_DEVICE_ID,
                        value = deviceId
                    )
                )
                AppLogger.i(TAG, "Set device id: $deviceId")
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting device id", e)
                Result.failure(e)
            }
        }
    }

    // ============================================
    // SYNC TOKEN
    // ============================================

    /**
     * Get the sync token for incremental sync.
     */
    suspend fun getSyncToken(): Result<String?> {
        return withContext(Dispatchers.IO) {
            try {
                val value = syncMetaDao.getValue(SyncMetaEntity.KEY_SYNC_TOKEN)
                Result.success(value)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting sync token", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Set the sync token.
     */
    suspend fun setSyncToken(token: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                syncMetaDao.upsert(
                    SyncMetaEntity(
                        key = SyncMetaEntity.KEY_SYNC_TOKEN,
                        value = token
                    )
                )
                AppLogger.d(TAG, "Set sync token")
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting sync token", e)
                Result.failure(e)
            }
        }
    }

    // ============================================
    // SYNC IN PROGRESS (for resume on interruption)
    // ============================================

    /**
     * Check if a sync was in progress (interrupted).
     */
    suspend fun isSyncInProgress(): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val value = syncMetaDao.getValue(SyncMetaEntity.KEY_SYNC_IN_PROGRESS)
                Result.success(value == "true")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting sync in progress", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Set the sync in progress flag.
     * Set to true before sync starts, false when it completes.
     */
    suspend fun setSyncInProgress(inProgress: Boolean): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                syncMetaDao.upsert(
                    SyncMetaEntity(
                        key = SyncMetaEntity.KEY_SYNC_IN_PROGRESS,
                        value = inProgress.toString()
                    )
                )
                AppLogger.d(TAG, "Set sync in progress: $inProgress")
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting sync in progress", e)
                Result.failure(e)
            }
        }
    }

    // ============================================
    // UTILITY
    // ============================================

    /**
     * Clear all sync metadata.
     * Used during hard sync.
     */
    suspend fun clearAll(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                syncMetaDao.deleteAll()
                AppLogger.i(TAG, "Cleared all sync metadata")
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error clearing sync metadata", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get a generic metadata value.
     */
    suspend fun getValue(key: String): Result<String?> {
        return withContext(Dispatchers.IO) {
            try {
                val value = syncMetaDao.getValue(key)
                Result.success(value)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting value for key: $key", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Set a generic metadata value.
     */
    suspend fun setValue(key: String, value: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                syncMetaDao.upsert(SyncMetaEntity(key = key, value = value))
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting value for key: $key", e)
                Result.failure(e)
            }
        }
    }
}
