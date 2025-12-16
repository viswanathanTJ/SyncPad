package com.viswa2k.syncpad.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.viswa2k.syncpad.BuildConfig
import com.viswa2k.syncpad.repository.BlogRepository
import com.viswa2k.syncpad.repository.PrefixIndexRepository
import com.viswa2k.syncpad.repository.SyncRepository
import com.viswa2k.syncpad.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Sync manager for handling data synchronization with Supabase.
 * 
 * Uses real HTTP calls to Supabase REST API for sync.
 * 
 * Sync conditions:
 * - Incremental: created_at > last_sync_time OR updated_at > last_sync_time
 * - Hard sync: clears DB and downloads all from server
 */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blogRepository: BlogRepository,
    private val syncRepository: SyncRepository,
    private val prefixIndexRepository: PrefixIndexRepository,
    private val supabaseApi: SupabaseApi
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
     * Check if network is available.
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Perform an incremental sync.
     * 
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
                
                // Check network connectivity
                if (!isNetworkAvailable()) {
                    AppLogger.w(TAG, "No internet connection available")
                    return@withContext Result.failure(
                        Exception("No internet connection. Please check your network and try again.")
                    )
                }
                
                // Check if sync is configured
                if (!isSyncConfigured()) {
                    AppLogger.w(TAG, "Sync not configured - API key or base URL missing")
                    return@withContext Result.failure(
                        Exception("Sync not configured. Please add SYNC_API_KEY and SYNC_BASE_URL to local.properties")
                    )
                }
                
                // Get last sync time
                val lastSyncTime = syncRepository.getLastSyncTime().getOrNull() ?: 0L
                AppLogger.d(TAG, "Last sync time: $lastSyncTime")
                
                var downloadedCount = 0
                var uploadedCount = 0
                val affectedPrefixes = mutableSetOf<String>()
                
                // STEP 1: Download from server
                val downloadResult = supabaseApi.getBlogsAfter(lastSyncTime)
                downloadResult.fold(
                    onSuccess = { serverBlogs ->
                        AppLogger.d(TAG, "Received ${serverBlogs.size} blogs from server")
                        
                        // Convert to entities and insert into local DB
                        val entities = serverBlogs.map { it.toBlogEntity() }
                        if (entities.isNotEmpty()) {
                            blogRepository.insertBlogs(entities)
                            downloadedCount = entities.size
                            affectedPrefixes.addAll(entities.map { it.titlePrefix })
                        }
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Failed to download from server", e)
                        return@withContext Result.failure(e)
                    }
                )
                
                // STEP 2: Upload local changes to server
                val localChanges = blogRepository.getBlogsForSync(lastSyncTime).getOrNull() ?: emptyList()
                if (localChanges.isNotEmpty()) {
                    AppLogger.d(TAG, "Found ${localChanges.size} local changes to upload")
                    
                    val uploadDtos = localChanges.map { BlogDto.fromBlogEntity(it) }
                    val uploadResult = supabaseApi.upsertBlogs(uploadDtos)
                    
                    uploadResult.fold(
                        onSuccess = { count ->
                            uploadedCount = count
                            affectedPrefixes.addAll(localChanges.map { it.titlePrefix })
                        },
                        onFailure = { e ->
                            AppLogger.e(TAG, "Failed to upload to server", e)
                            // Don't fail the entire sync, just log the error
                        }
                    )
                }
                
                // STEP 3: Update last sync time
                val now = System.currentTimeMillis()
                syncRepository.setLastSyncTime(now)
                
                // STEP 4: Update prefix index for affected prefixes
                if (affectedPrefixes.isNotEmpty()) {
                    prefixIndexRepository.partialUpdate(affectedPrefixes)
                }
                
                val result = SyncResult(
                    downloaded = downloadedCount,
                    uploaded = uploadedCount,
                    deleted = 0,
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
     * Perform a hard sync (clear local and download all from server).
     * 
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
                
                // Check network connectivity
                if (!isNetworkAvailable()) {
                    AppLogger.w(TAG, "No internet connection available")
                    return@withContext Result.failure(
                        Exception("No internet connection. Please check your network and try again.")
                    )
                }
                
                // Check if sync is configured
                if (!isSyncConfigured()) {
                    return@withContext Result.failure(
                        Exception("Sync not configured. Please add SYNC_API_KEY and SYNC_BASE_URL to local.properties")
                    )
                }
                
                // Get count before clearing for deleted count
                val previousCount = blogRepository.getBlogCount().getOrNull() ?: 0
                
                // Clear local data
                blogRepository.deleteAllBlogs()
                syncRepository.clearAll()
                prefixIndexRepository.clearIndex()
                
                AppLogger.d(TAG, "Cleared local data ($previousCount items)")
                
                // Download all blogs from server
                var downloadedCount = 0
                val downloadResult = supabaseApi.getAllBlogs()
                downloadResult.fold(
                    onSuccess = { serverBlogs ->
                        AppLogger.d(TAG, "Received ${serverBlogs.size} blogs from server")
                        
                        // Convert to entities and insert into local DB
                        val entities = serverBlogs.map { it.toBlogEntity() }
                        if (entities.isNotEmpty()) {
                            blogRepository.insertBlogs(entities)
                            downloadedCount = entities.size
                        }
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Failed to download from server", e)
                        return@withContext Result.failure(e)
                    }
                )
                
                // Update last sync time
                val now = System.currentTimeMillis()
                syncRepository.setLastSyncTime(now)
                
                // Rebuild prefix index
                prefixIndexRepository.rebuildIndex()
                
                val result = SyncResult(
                    downloaded = downloadedCount,
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
