package com.example.largeindexblog.sync

import com.example.largeindexblog.BuildConfig
import com.example.largeindexblog.repository.BlogRepository
import com.example.largeindexblog.repository.PrefixIndexRepository
import com.example.largeindexblog.repository.SyncRepository
import com.example.largeindexblog.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a sync operation with detailed counts.
 */
data class SyncResult(
    val downloaded: Int = 0,
    val uploaded: Int = 0,
    val deleted: Int = 0,
    val isSuccess: Boolean = true,
    val message: String = ""
) {
    val totalChanges: Int get() = downloaded + uploaded + deleted
    
    fun toDisplayString(): String {
        return when {
            !isSuccess -> message
            totalChanges == 0 -> "Already up to date"
            else -> "↓$downloaded ↑$uploaded ✕$deleted"
        }
    }
}

/**
 * Sync manager for handling data synchronization.
 * 
 * This is a PLACEHOLDER implementation for local-first development.
 * No real backend is required - code is structured for future integration.
 * 
 * Sync conditions:
 * - Incremental: created_at > last_sync_time OR updated_at > last_sync_time
 * - Hard sync: clears DB and rebuilds index
 * - last_sync_time stored locally only
 */
@Singleton
class SyncManager @Inject constructor(
    private val blogRepository: BlogRepository,
    private val syncRepository: SyncRepository,
    private val prefixIndexRepository: PrefixIndexRepository
) {
    companion object {
        private const val TAG = "SyncManager"
    }

    init {
        // Log warning if sync secrets are not configured
        if (BuildConfig.SYNC_API_KEY.isEmpty()) {
            AppLogger.logSecretsMissing("SYNC_API_KEY")
        }
        if (BuildConfig.SYNC_BASE_URL.isEmpty()) {
            AppLogger.logSecretsMissing("SYNC_BASE_URL")
        }
    }

    /**
     * Perform an incremental sync.
     * 
     * In a real implementation, this would:
     * 1. Get last_sync_time from local storage
     * 2. Fetch changes from server where created_at > last_sync_time OR updated_at > last_sync_time
     * 3. Apply changes to local database
     * 4. Push local changes to server
     * 5. Update last_sync_time
     * 
     * @return SyncResult with counts of downloaded, uploaded, and deleted items
     */
    suspend fun performIncrementalSync(): Result<SyncResult> {
        return withContext(Dispatchers.IO) {
            try {
                AppLogger.i(TAG, "Starting incremental sync")
                
                // Get last sync time
                val lastSyncTime = syncRepository.getLastSyncTime().getOrNull() ?: 0L
                AppLogger.d(TAG, "Last sync time: $lastSyncTime")
                
                // Get local changes since last sync (these would be uploaded)
                val localChanges = blogRepository.getBlogsForSync(lastSyncTime).getOrNull() ?: emptyList()
                val uploadedCount = localChanges.size
                AppLogger.d(TAG, "Found $uploadedCount local changes to upload")
                
                // PLACEHOLDER: In a real implementation, this would:
                // 1. Send localChanges to server
                // 2. Receive server changes (downloadedCount)
                // 3. Handle deletions (deletedCount)
                
                // Simulate receiving some downloads from server
                val downloadedCount = 0 // Would come from server response
                val deletedCount = 0 // Would come from server response
                
                // Update last sync time
                val now = System.currentTimeMillis()
                syncRepository.setLastSyncTime(now)
                
                // Update prefix index for any affected prefixes
                val affectedPrefixes = localChanges.map { it.titlePrefix }.toSet()
                if (affectedPrefixes.isNotEmpty()) {
                    prefixIndexRepository.partialUpdate(affectedPrefixes)
                }
                
                val result = SyncResult(
                    downloaded = downloadedCount,
                    uploaded = uploadedCount,
                    deleted = deletedCount,
                    isSuccess = true,
                    message = "Sync completed"
                )
                
                AppLogger.i(TAG, "Incremental sync complete: ${result.toDisplayString()}")
                Result.success(result)
                
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in incremental sync", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Perform a hard sync (clear and rebuild).
     * 
     * In a real implementation, this would:
     * 1. Clear local database
     * 2. Fetch all data from server
     * 3. Insert into local database
     * 4. Rebuild prefix index
     * 5. Reset last_sync_time
     * 
     * @return SyncResult with counts
     */
    suspend fun performHardSync(): Result<SyncResult> {
        return withContext(Dispatchers.IO) {
            try {
                AppLogger.i(TAG, "Starting hard sync")
                
                // Get count before clearing for deleted count
                val previousCount = blogRepository.getBlogCount().getOrNull() ?: 0
                
                // Clear local data
                blogRepository.deleteAllBlogs()
                syncRepository.clearAll()
                prefixIndexRepository.clearIndex()
                
                AppLogger.d(TAG, "Cleared local data ($previousCount items)")
                
                // PLACEHOLDER: In a real implementation, this would:
                // 1. Fetch all data from server
                // 2. Insert into local database
                
                // Simulate by just setting the sync time
                val now = System.currentTimeMillis()
                syncRepository.setLastSyncTime(now)
                
                // Rebuild prefix index
                prefixIndexRepository.rebuildIndex()
                
                // Get new count
                val newCount = blogRepository.getBlogCount().getOrNull() ?: 0
                
                val result = SyncResult(
                    downloaded = newCount,
                    uploaded = 0,
                    deleted = previousCount,
                    isSuccess = true,
                    message = "Hard sync completed"
                )
                
                AppLogger.i(TAG, "Hard sync complete: ${result.toDisplayString()}")
                Result.success(result)
                
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in hard sync", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Check if sync is configured (API key and base URL available).
     */
    fun isSyncConfigured(): Boolean {
        return BuildConfig.SYNC_API_KEY.isNotEmpty() && BuildConfig.SYNC_BASE_URL.isNotEmpty()
    }

    /**
     * Get the sync base URL.
     * Returns null if not configured.
     */
    fun getSyncBaseUrl(): String? {
        return BuildConfig.SYNC_BASE_URL.ifEmpty { null }
    }
}
