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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
     * Application-scoped coroutine scope for sync operations.
     * This scope survives ViewModel lifecycle changes (navigation).
     * Uses SupervisorJob so failures don't cancel sibling jobs.
     */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    
    /**
     * Result of the last sync operation (for UI to query after navigation).
     * Used by syncs launched via launchHardSync to communicate results.
     */
    private val _lastSyncResult = MutableStateFlow<Result<SyncResult>?>(null)
    val lastSyncResult: StateFlow<Result<SyncResult>?> = _lastSyncResult.asStateFlow()
    
    /**
     * Clear the last sync result after UI has processed it.
     */
    fun clearLastSyncResult() {
        _lastSyncResult.value = null
    }

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
     * Launch hard sync in application scope.
     * This ensures the sync survives navigation (e.g., from Settings to Home).
     * The result can be observed via lastSyncResult StateFlow.
     * 
     * Pre-checks isSyncRunning to prevent race conditions from launching
     * multiple coroutines before the first one sets the flag.
     */
    fun launchHardSync() {
        // Pre-check BEFORE launching coroutine to prevent race conditions
        if (isSyncRunning) {
            AppLogger.w(TAG, "launchHardSync: Sync already running, skipping")
            return
        }
        syncScope.launch {
            val result = performHardSync()
            _lastSyncResult.value = result
        }
    }
    
    /**
     * Launch incremental sync in application scope.
     * This ensures the sync survives navigation.
     * The result can be observed via lastSyncResult StateFlow.
     * 
     * Pre-checks isSyncRunning to prevent race conditions.
     */
    fun launchIncrementalSync() {
        // Pre-check BEFORE launching coroutine to prevent race conditions
        if (isSyncRunning) {
            AppLogger.w(TAG, "launchIncrementalSync: Sync already running, skipping")
            return
        }
        syncScope.launch {
            val result = performIncrementalSync()
            _lastSyncResult.value = result
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
                        clearProgress()
                        
                        // For network interruptions (app minimized, WiFi lost), keep sync progress
                        // so it auto-resumes on next app launch. For other errors, clear progress.
                        if (e is NetworkInterruptedException) {
                            AppLogger.i(TAG, "Network interrupted - sync will resume on next launch")
                            // Keep syncInProgress = true so it auto-resumes
                        } else {
                            // Clear progress to prevent infinite error loops
                            syncRepository.setSyncInProgress(false)
                        }
                        
                        return@withContext Result.failure(e)
                    }
                )
                
                // STEP 1.5: Handle server-side deletions
                // Fetch blogs marked as is_deleted=true on server since last sync
                var deletedCount = 0
                updateProgress("Checking deletions...")
                
                val deletedIds = supabaseApi.getDeletedBlogIds(lastSyncTime).getOrNull() ?: emptyList()
                
                if (deletedIds.isNotEmpty()) {
                    AppLogger.i(TAG, "Found ${deletedIds.size} soft-deleted blogs from server")
                    
                    // Get prefixes of blogs being deleted for index update
                    for (id in deletedIds) {
                        blogRepository.getTitlePrefixById(id).getOrNull()?.let { prefix ->
                            affectedPrefixes.add(prefix)
                        }
                    }
                    
                    // Hard delete from local database (server already marked them as deleted)
                    deletedCount = blogRepository.deleteBlogsByIds(deletedIds).getOrNull() ?: 0
                    AppLogger.i(TAG, "Removed $deletedCount locally that were deleted on server")
                }
                
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
                if (receivedFromServer > 0 || deletedCount > 0) {
                    blogRepository.notifyDataChanged()
                }
                
                // STEP 6: Clear sync progress tracking (successful completion)
                syncRepository.clearSyncProgress()
                isSyncRunning = false
                clearProgress()
                
                val result = SyncResult(
                    downloaded = downloadedCount,
                    uploaded = uploadedCount,
                    deleted = deletedCount,
                    isSuccess = true,
                    message = if (downloadedCount == 0 && uploadedCount == 0 && deletedCount == 0) "Already in sync" else "Sync completed"
                )
                
                AppLogger.i(TAG, "Incremental sync complete: ${result.toDisplayString()}")
                Result.success(result)
                
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in incremental sync", e)
                // CRITICAL: Clear both runtime flag AND persisted sync progress
                // to prevent false "interrupted" detection on next app launch
                isSyncRunning = false
                syncRepository.setSyncInProgress(false)
                clearProgress()
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
                // Prevent concurrent syncs (runtime check)
                if (isSyncRunning) {
                    AppLogger.w(TAG, "Sync already running, skipping hard sync")
                    return@withContext Result.failure(
                        Exception("Sync already in progress")
                    )
                }
                
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
                
                // Mark sync as running (runtime flag)
                isSyncRunning = true
                updateProgress("Preparing hard sync...")
                
                // Get count before clearing for deleted count
                val previousCount = blogRepository.getBlogCount().getOrNull() ?: 0
                
                // Clear local data
                updateProgress("Clearing local data...")
                blogRepository.deleteAllBlogs()
                syncRepository.clearAll()
                prefixIndexRepository.clearIndex()
                
                AppLogger.d(TAG, "Cleared local data ($previousCount items)")
                
                // Get server count for progress display
                updateProgress("Counting records...")
                val totalExpected = supabaseApi.getServerCount(0L).getOrNull() ?: 0
                
                // Download all blogs from server using streaming to avoid OOM
                var downloadedCount = 0
                updateProgress("Downloading notes...", 0)
                
                val streamResult = supabaseApi.streamAllBlogs { blogDto ->
                    val entity = blogDto.toBlogEntity()
                    blogRepository.insertBlogSilent(entity)
                    downloadedCount++
                    
                    // Update progress every 50 items
                    if (downloadedCount % 50 == 0) {
                        val percent = if (totalExpected > 0) (downloadedCount * 100 / totalExpected) else 0
                        updateProgress("Downloading... $percent%", downloadedCount)
                    }
                }
                
                streamResult.fold(
                    onSuccess = { count ->
                        AppLogger.d(TAG, "Streamed $count blogs from server")
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Failed to stream from server", e)
                        isSyncRunning = false
                        clearProgress()
                        
                        // For network interruptions, provide a clear message
                        // Note: Hard sync doesn't resume (we already cleared data)
                        // so we always clear the progress flag here
                        
                        return@withContext Result.failure(e)
                    }
                )
                
                // Update last sync time
                val now = System.currentTimeMillis()
                syncRepository.setLastSyncTime(now)
                
                // Rebuild prefix index
                updateProgress("Rebuilding index...")
                prefixIndexRepository.rebuildIndex()
                
                // Notify UI once after all inserts complete
                if (downloadedCount > 0) {
                    blogRepository.notifyDataChanged()
                }
                
                // Clear sync running flag and progress
                isSyncRunning = false
                clearProgress()
                
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
                // Clear both runtime flag AND persisted sync progress
                isSyncRunning = false
                syncRepository.setSyncInProgress(false)
                clearProgress()
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

    /**
     * Delete a blog from the server by moving it to recycle_bin.
     * This is called when a blog is deleted locally.
     * 
     * @param blogId The ID of the blog to delete from server
     * @return Result indicating success or failure
     */
    suspend fun deleteBlogFromServer(blogId: Long): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Check network connectivity
                if (!isNetworkAvailable()) {
                    AppLogger.w(TAG, "No internet connection for blog delete sync")
                    return@withContext Result.failure(
                        Exception("No internet connection")
                    )
                }
                
                // Check if sync is configured
                if (!isSyncConfigured()) {
                    AppLogger.w(TAG, "Sync not configured for blog delete")
                    return@withContext Result.failure(
                        Exception("Sync not configured")
                    )
                }
                
                AppLogger.d(TAG, "Soft deleting blog on server: $blogId")
                
                // Soft delete on server (set is_deleted = true)
                supabaseApi.softDeleteOnServer(blogId)
                
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error deleting blog from server: $blogId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Launch server delete in application scope.
     * This survives ViewModel destruction (navigation).
     * Calls onFailure callback if server delete fails, so caller can restore local data.
     * 
     * @param blogId The ID of the blog to delete
     * @param blogBackup The backup data to restore on failure
     * @param onResult Callback called with error (null if success)
     */
    fun launchServerDelete(
        blogId: Long,
        blogBackup: com.viswa2k.syncpad.data.entity.BlogEntity,
        onResult: (Exception?) -> Unit
    ) {
        syncScope.launch {
            try {
                val result = deleteBlogFromServer(blogId)
                
                result.fold(
                    onSuccess = {
                        AppLogger.i(TAG, "Server delete completed: $blogId")
                        // Call callback on main thread
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onResult(null)
                        }
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Server delete failed, calling rollback: $blogId", e)
                        // Call callback on main thread with error
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onResult(e as? Exception ?: Exception(e.message))
                        }
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Exception in launchServerDelete: $blogId", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(e)
                }
            }
        }
    }
}
