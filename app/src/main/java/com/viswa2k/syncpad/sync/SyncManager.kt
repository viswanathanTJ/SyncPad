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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * Runtime flag to prevent concurrent syncs.
     * Different from sync_in_progress in DB which persists across app restarts.
     */
    @Volatile
    private var isSyncRunning = false

    /**
     * Check if a sync is currently running (runtime check).
     * Use this to prevent starting another sync.
     */
    fun isSyncCurrentlyRunning(): Boolean = isSyncRunning

    /**
     * Progress updates during sync.
     * Emits (message, count) pairs for UI display.
     */
    private val _syncProgress = MutableStateFlow<Pair<String, Int>?>(null)
    val syncProgress: StateFlow<Pair<String, Int>?> = _syncProgress.asStateFlow()

    private fun updateProgress(message: String, count: Int = 0) {
        _syncProgress.value = Pair(message, count)
    }
    
    private fun clearProgress() {
        _syncProgress.value = null
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
     * Get last sync time.
     */
    suspend fun getLastSyncTime(): Long {
        return syncRepository.getLastSyncTime().getOrNull() ?: 0L
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
                // Prevent concurrent syncs (runtime check)
                if (isSyncRunning) {
                    AppLogger.w(TAG, "Sync already running, skipping")
                    return@withContext Result.failure(
                        Exception("Sync already in progress")
                    )
                }
                
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
                
                // Mark sync as running (runtime flag)
                isSyncRunning = true
                
                // Mark sync as in progress (for resume on interruption - persisted)
                syncRepository.setSyncInProgress(true)
                updateProgress("Connecting...")
                
                // Get last sync time
                val lastSyncTime = syncRepository.getLastSyncTime().getOrNull() ?: 0L
                AppLogger.i(TAG, "Last sync time: $lastSyncTime (${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(lastSyncTime))})")
                
                // Check if we're resuming an interrupted sync
                val resumeFromId = syncRepository.getSyncLastId().getOrNull()
                val isResuming = resumeFromId != null && resumeFromId > 0
                if (isResuming) {
                    AppLogger.i(TAG, "Resuming sync from blog ID: $resumeFromId")
                }
                
                var downloadedCount = 0
                var uploadedCount = 0
                var receivedFromServer = 0  // Track items received from server (even if duplicates)
                val affectedPrefixes = mutableSetOf<String>()
                
                // Get existing local count for comparison
                val localBlogCount = blogRepository.getBlogCount().getOrNull() ?: 0
                
                // Get server count for percentage display
                var totalExpected = syncRepository.getSyncTotalExpected().getOrNull() ?: 0
                if (totalExpected == 0 && !isResuming) {
                    updateProgress("Counting records...")
                    totalExpected = supabaseApi.getServerCount(lastSyncTime).getOrNull() ?: 0
                    if (totalExpected > 0) {
                        syncRepository.setSyncTotalExpected(totalExpected)
                        AppLogger.i(TAG, "Total expected: $totalExpected blogs to sync")
                    }
                }
                
                // Helper to calculate percentage
                fun getPercentage(): Int = if (totalExpected > 0) (receivedFromServer * 100 / totalExpected) else 0
                
                // STEP 1: Download from server using streaming to avoid OOM
                // Each blog is inserted directly to DB as it's parsed (silent = no UI refresh per item)
                updateProgress("Downloading notes...", 0)
                
                // Use resume-aware streaming if we're resuming
                val streamResult = if (isResuming && resumeFromId != null) {
                    supabaseApi.streamBlogsAfterId(lastSyncTime, resumeFromId) { blogDto ->
                        val entity = blogDto.toBlogEntity()
                        blogRepository.insertBlogSilent(entity)
                        affectedPrefixes.add(entity.titlePrefix)
                        receivedFromServer++
                        
                        // Track last synced ID for resume capability
                        blogDto.id?.let { syncRepository.setSyncLastId(it) }
                        
                        // Update progress with percentage every 50 items
                        if (receivedFromServer % 50 == 0) {
                            val percent = getPercentage()
                            updateProgress("Downloading... $percent%", receivedFromServer)
                        }
                    }
                } else {
                    supabaseApi.streamBlogsAfter(lastSyncTime) { blogDto ->
                        val entity = blogDto.toBlogEntity()
                        blogRepository.insertBlogSilent(entity)
                        affectedPrefixes.add(entity.titlePrefix)
                        receivedFromServer++
                        
                        // Track last synced ID for resume capability
                        blogDto.id?.let { syncRepository.setSyncLastId(it) }
                        
                        // Update progress with percentage every 50 items
                        if (receivedFromServer % 50 == 0) {
                            val percent = getPercentage()
                            updateProgress("Downloading... $percent%", receivedFromServer)
                        }
                    }
                }
                
                streamResult.fold(
                    onSuccess = { count ->
                        AppLogger.i(TAG, "Streamed $count blogs from server for sync")
                        
                        // Count only truly NEW items (not updates)
                        val newBlogCount = blogRepository.getBlogCount().getOrNull() ?: 0
                        downloadedCount = maxOf(0, newBlogCount - localBlogCount)
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Failed to stream from server", e)
                        isSyncRunning = false
                        return@withContext Result.failure(e)
                    }
                )
                
                // STEP 2: Upload local changes to server
                // ONLY upload if:
                // - lastSyncTime > 0 (not first sync)
                // - receivedFromServer == 0 (server returned nothing, so we have local-only changes)
                if (lastSyncTime > 0 && receivedFromServer == 0) {
                    val localChanges = blogRepository.getBlogsForSync(lastSyncTime).getOrNull() ?: emptyList()
                    if (localChanges.isNotEmpty()) {
                        AppLogger.d(TAG, "Found ${localChanges.size} local changes to upload")
                        updateProgress("Uploading notes...", localChanges.size)
                        
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
                }
                
                // STEP 3: Update last sync time
                val now = System.currentTimeMillis()
                syncRepository.setLastSyncTime(now)
                
                // STEP 4: Update prefix index for affected prefixes
                if (affectedPrefixes.isNotEmpty()) {
                    updateProgress("Updating index...", affectedPrefixes.size)
                    prefixIndexRepository.partialUpdate(affectedPrefixes)
                }
                
                // STEP 5: Notify data changed once (not per-blog) to refresh UI
                if (receivedFromServer > 0) {
                    blogRepository.notifyDataChanged()
                }
                
                // STEP 6: Clear sync progress tracking (successful completion)
                syncRepository.clearSyncProgress()
                isSyncRunning = false
                clearProgress()
                
                val result = SyncResult(
                    downloaded = downloadedCount,
                    uploaded = uploadedCount,
                    deleted = 0,
                    isSuccess = true,
                    message = if (downloadedCount == 0 && uploadedCount == 0) "Already in sync" else "Sync completed"
                )
                
                AppLogger.i(TAG, "Incremental sync complete: ${result.toDisplayString()}")
                Result.success(result)
                
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in incremental sync", e)
                isSyncRunning = false
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
                
                // Download all blogs from server using streaming to avoid OOM
                var downloadedCount = 0
                val streamResult = supabaseApi.streamAllBlogs { blogDto ->
                    val entity = blogDto.toBlogEntity()
                    blogRepository.insertBlogSilent(entity)
                    downloadedCount++
                }
                
                streamResult.fold(
                    onSuccess = { count ->
                        AppLogger.d(TAG, "Streamed $count blogs from server")
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Failed to stream from server", e)
                        return@withContext Result.failure(e)
                    }
                )
                
                // Update last sync time
                val now = System.currentTimeMillis()
                syncRepository.setLastSyncTime(now)
                
                // Rebuild prefix index
                prefixIndexRepository.rebuildIndex()
                
                // Notify UI once after all inserts complete
                if (downloadedCount > 0) {
                    blogRepository.notifyDataChanged()
                }
                
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
    
    /**
     * Check if a sync was in progress (interrupted).
     * Used to auto-resume on app restart.
     */
    suspend fun wasSyncInterrupted(): Boolean {
        return syncRepository.isSyncInProgress().getOrNull() == true
    }
    
    /**
     * Upload a single blog to the server.
     * This is a lightweight operation for immediate sync after save.
     * Does NOT download from server or update sync time.
     * 
     * @param blogId The ID of the blog to upload
     * @return Result indicating success or failure
     */
    suspend fun uploadSingleBlog(blogId: Long): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Check network connectivity
                if (!isNetworkAvailable()) {
                    AppLogger.w(TAG, "No internet connection for single blog upload")
                    return@withContext Result.failure(
                        Exception("No internet connection")
                    )
                }
                
                // Check if sync is configured
                if (!isSyncConfigured()) {
                    AppLogger.w(TAG, "Sync not configured for single blog upload")
                    return@withContext Result.failure(
                        Exception("Sync not configured")
                    )
                }
                
                // Get the blog from local DB
                val blog = blogRepository.getBlogById(blogId).getOrNull()
                if (blog == null) {
                    AppLogger.w(TAG, "Blog not found for upload: $blogId")
                    return@withContext Result.failure(
                        Exception("Blog not found")
                    )
                }
                
                AppLogger.d(TAG, "Uploading single blog: $blogId")
                
                // Upload to server
                val dto = BlogDto.fromBlogEntity(blog)
                val result = supabaseApi.upsertBlogs(listOf(dto))
                
                result.fold(
                    onSuccess = { count ->
                        AppLogger.i(TAG, "Single blog uploaded successfully: $blogId")
                        // Update last sync time so incremental sync doesn't re-upload
                        syncRepository.setLastSyncTime(System.currentTimeMillis())
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Failed to upload single blog: $blogId", e)
                    }
                )
                
                result.map { }
                
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error uploading single blog: $blogId", e)
                Result.failure(e)
            }
        }
    }
}
