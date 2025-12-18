package com.viswa2k.syncpad.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.viswa2k.syncpad.data.entity.PrefixIndexEntity
import com.viswa2k.syncpad.data.model.BlogListItem
import com.viswa2k.syncpad.repository.BlogRepository
import com.viswa2k.syncpad.repository.PrefixIndexRepository
import com.viswa2k.syncpad.repository.SettingsRepository
import com.viswa2k.syncpad.sync.SyncManager
import com.viswa2k.syncpad.ui.state.IndexState
import com.viswa2k.syncpad.ui.state.SyncState
import com.viswa2k.syncpad.ui.state.UiState
import com.viswa2k.syncpad.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the blog list screen.
 * Handles paging, alphabet navigation, sync, and list state.
 */
@HiltViewModel
class BlogListViewModel @Inject constructor(
    private val blogRepository: BlogRepository,
    private val prefixIndexRepository: PrefixIndexRepository,
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager
) : ViewModel() {

    companion object {
        private const val TAG = "BlogListViewModel"
    }

    // ============================================
    // PREFIX FILTER STATE
    // ============================================

    private val _prefixFilter = MutableStateFlow<String?>(null)
    val prefixFilter: StateFlow<String?> = _prefixFilter.asStateFlow()

    // ============================================
    // REFRESH TRIGGER
    // ============================================
    
    // Increment this to trigger a refresh of the paging data
    private val _refreshTrigger = MutableStateFlow(0)

    // ============================================
    // PAGED BLOGS
    // ============================================

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedBlogs: Flow<PagingData<BlogListItem>> = combine(
        _prefixFilter,
        _refreshTrigger
    ) { prefix, _ -> prefix }
        .flatMapLatest { prefix ->
            blogRepository.getPagedBlogs(prefix)
        }
        .cachedIn(viewModelScope)

    // ============================================
    // ALPHABET INDEX
    // ============================================

    private val _alphabetIndex = MutableStateFlow<UiState<List<PrefixIndexEntity>>>(UiState.Loading)
    val alphabetIndex: StateFlow<UiState<List<PrefixIndexEntity>>> = _alphabetIndex.asStateFlow()

    // ============================================
    // BLOG COUNT
    // ============================================

    val blogCount: StateFlow<Int> = blogRepository.getBlogCountFlow()
        .catch { e ->
            AppLogger.e(TAG, "Error in blog count flow", e)
            emit(0)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    // Filtered count - tracks actual count for current prefix filter
    private val _filteredCount = MutableStateFlow(0)
    val filteredCount: StateFlow<Int> = _filteredCount.asStateFlow()

    /**
     * Get count for a specific prefix (for section header).
     */
    suspend fun getCountByPrefix(prefix: String): Int {
        return blogRepository.getCountByPrefix(prefix).getOrNull() ?: 0
    }

    // ============================================
    // SETTINGS
    // ============================================

    val showBottomIndex: StateFlow<Boolean> = settingsRepository.getShowBottomIndexFlow()
        .catch { e ->
            AppLogger.e(TAG, "Error in show bottom index flow", e)
            emit(false)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val fontSize: StateFlow<Int> = settingsRepository.getFontSizeFlow()
        .catch { e ->
            AppLogger.e(TAG, "Error in font size flow", e)
            emit(SettingsRepository.DEFAULT_FONT_SIZE)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsRepository.DEFAULT_FONT_SIZE
        )

    val maxDepth: StateFlow<Int> = settingsRepository.getMaxDepthFlow()
        .catch { e ->
            AppLogger.e(TAG, "Error in max depth flow", e)
            emit(SettingsRepository.DEFAULT_MAX_DEPTH)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsRepository.DEFAULT_MAX_DEPTH
        )

    // ============================================
    // INDEX STATE
    // ============================================

    private val _indexState = MutableStateFlow<IndexState>(IndexState.Idle)
    val indexState: StateFlow<IndexState> = _indexState.asStateFlow()

    // ============================================
    // SYNC STATE
    // ============================================

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    init {
        loadAlphabetIndex()
        
        // Immediately check if a sync is running and set initial state
        // This prevents showing "Idle" when ViewModel is recreated during an active sync
        if (syncManager.isSyncCurrentlyRunning()) {
            val currentProgress = syncManager.syncProgress.value
            _syncState.value = if (currentProgress != null) {
                SyncState.Syncing(message = currentProgress.first, count = currentProgress.second)
            } else {
                SyncState.Syncing()
            }
        }
        
        // Listen for data changes from other components (e.g. AddBlogViewModel, Sync)
        viewModelScope.launch {
            blogRepository.dataChanged.collect {
                AppLogger.d(TAG, "Data changed event received, refreshing list")
                refreshList()
            }
        }
        
        // Observe sync progress from SyncManager
        // This handles syncs started from Settings screen or other sources
        viewModelScope.launch {
            syncManager.syncProgress.collect { progress ->
                if (progress != null) {
                    // If SyncManager is running a sync (from any source), show progress
                    if (syncManager.isSyncCurrentlyRunning() || _syncState.value.isSyncing) {
                        _syncState.value = SyncState.Syncing(
                            message = progress.first,
                            count = progress.second
                        )
                    }
                } else if (syncManager.isSyncCurrentlyRunning()) {
                    // Sync is running but no progress message yet - show default state
                    if (!_syncState.value.isSyncing) {
                        _syncState.value = SyncState.Syncing()
                    }
                }
            }
        }
        
        // Observe sync results from syncs launched via app scope (e.g., hard sync from Settings)
        // This ensures we get the result even after navigating from Settings to Home
        viewModelScope.launch {
            syncManager.lastSyncResult.collect { result ->
                if (result != null) {
                    result.fold(
                        onSuccess = { syncResult ->
                            AppLogger.i(TAG, "Sync completed from app scope: ${syncResult.toDisplayString()}")
                            _syncState.value = SyncState.Success(syncResult, isManual = true)
                            refreshList()
                        },
                        onFailure = { e ->
                            AppLogger.e(TAG, "Sync failed from app scope", e)
                            _syncState.value = SyncState.Error(
                                message = e.message ?: "Sync failed",
                                exception = e
                            )
                        }
                    )
                    // Clear after processing to avoid re-triggering
                    syncManager.clearLastSyncResult()
                }
            }
        }
    }

    // ============================================
    // ACTIONS
    // ============================================

    /**
     * Refresh the list by triggering a new paging load.
     * Also checks if current filter is now empty and clears it.
     */
    fun refreshList() {
        AppLogger.d(TAG, "Refreshing list")
        _refreshTrigger.value = _refreshTrigger.value + 1
        loadAlphabetIndex()
        
        // Check if current filter has 0 items and clear it if so
        val currentFilter = _prefixFilter.value
        if (currentFilter != null) {
            viewModelScope.launch {
                val count = blogRepository.getCountByPrefix(currentFilter).getOrNull() ?: 0
                if (count == 0) {
                    AppLogger.d(TAG, "Filter '$currentFilter' has 0 items, clearing filter")
                    _prefixFilter.value = null
                }
            }
        }
    }

    /**
     * Load the alphabet index for sidebar navigation.
     */
    fun loadAlphabetIndex() {
        viewModelScope.launch {
            try {
                _alphabetIndex.value = UiState.Loading
                
                prefixIndexRepository.getAlphabetIndexFlow()
                    .catch { e ->
                        AppLogger.e(TAG, "Error loading alphabet index", e)
                        _alphabetIndex.value = UiState.Error(
                            message = "Failed to load index",
                            exception = e
                        )
                    }
                    .collect { indices ->
                        _alphabetIndex.value = UiState.Success(indices)
                    }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in loadAlphabetIndex", e)
                _alphabetIndex.value = UiState.Error(
                    message = "Failed to load index: ${e.message}",
                    exception = e
                )
            }
        }
    }

    /**
     * Filter blogs by prefix (when user taps alphabet sidebar).
     */
    fun filterByPrefix(prefix: String?) {
        AppLogger.d(TAG, "Filtering by prefix: $prefix")
        _prefixFilter.value = prefix
    }

    /**
     * Clear the prefix filter.
     */
    fun clearFilter() {
        _prefixFilter.value = null
    }

    /**
     * Rebuild the prefix index.
     */
    fun rebuildIndex() {
        viewModelScope.launch {
            try {
                _indexState.value = IndexState.Building
                
                val maxDepth = settingsRepository.getMaxDepthFlow()
                    .stateIn(viewModelScope)
                    .value

                val result = prefixIndexRepository.rebuildIndex(maxDepth)
                
                result.fold(
                    onSuccess = { count ->
                        _indexState.value = IndexState.Complete(count)
                        // Refresh list and sidebar
                        refreshList()
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Error rebuilding index", e)
                        _indexState.value = IndexState.Error(
                            message = "Failed to rebuild index: ${e.message}",
                            exception = e
                        )
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in rebuildIndex", e)
                _indexState.value = IndexState.Error(
                    message = "Failed to rebuild index: ${e.message}",
                    exception = e
                )
            }
        }
    }

    /**
     * Reset the index state to idle.
     */
    fun resetIndexState() {
        _indexState.value = IndexState.Idle
    }

    /**
     * Perform auto-sync on app start:
     * - On first install (never synced before)
     * - If a previous sync was interrupted (app closed/internet lost mid-sync)
     */
    fun performAutoSync() {
        viewModelScope.launch {
            try {
                if (!isSyncConfigured()) return@launch
                
                // Early guard: don't start auto-sync if a sync is already running
                // (e.g., hard sync from Settings that survived navigation)
                if (syncManager.isSyncCurrentlyRunning()) {
                    AppLogger.d(TAG, "Sync already running, skipping auto-sync")
                    return@launch
                }
                
                val lastSyncTime = syncManager.getLastSyncTime()
                val wasInterrupted = syncManager.wasSyncInterrupted()
                
                when {
                    wasInterrupted -> {
                        // Resume interrupted sync
                        AppLogger.i(TAG, "Previous sync was interrupted, resuming...")
                        performSync(isManual = false)
                    }
                    lastSyncTime == 0L -> {
                        // First install
                        AppLogger.i(TAG, "First install detected, performing initial sync")
                        performSync(isManual = false)
                    }
                    else -> {
                        AppLogger.d(TAG, "Already synced before and no interruption, skipping auto-sync")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in performAutoSync", e)
            }
        }
    }

    /**
     * Perform sync from the home screen.
     */
    fun performSync(isManual: Boolean = true) {
        viewModelScope.launch {
            try {
                // Don't update state if sync is already running
                if (syncManager.isSyncCurrentlyRunning()) {
                    AppLogger.d(TAG, "Sync already running, skipping")
                    return@launch
                }
                
                _syncState.value = SyncState.Syncing()
                
                val result = syncManager.performIncrementalSync()
                
                result.fold(
                    onSuccess = { syncResult ->
                        _syncState.value = SyncState.Success(syncResult, isManual)
                        // Refresh the list after sync
                        refreshList()
                    },
                    onFailure = { e ->
                        // If sync was already running, don't change state (another sync is active)
                        if (e.message?.contains("already in progress") == true) {
                            AppLogger.d(TAG, "Ignoring 'already in progress' error - sync is running")
                            return@fold
                        }
                        AppLogger.e(TAG, "Error in sync", e)
                        _syncState.value = SyncState.Error(
                            message = e.message ?: "Sync failed",
                            exception = e
                        )
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in performSync", e)
                _syncState.value = SyncState.Error(
                    message = e.message ?: "Sync failed",
                    exception = e
                )
            }
        }
    }

    /**
     * Reset the sync state to idle.
     */
    fun resetSyncState() {
        _syncState.value = SyncState.Idle
    }

    /**
     * Check if sync is configured (API key and base URL available).
     */
    fun isSyncConfigured(): Boolean {
        return syncManager.isSyncConfigured()
    }

    /**
     * Get the first blog ID for a given prefix (for scrolling to position).
     */
    suspend fun getFirstBlogIdForPrefix(prefix: String): Long? {
        return try {
            prefixIndexRepository.getFirstBlogId(prefix).getOrNull()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting first blog id for prefix: $prefix", e)
            null
        }
    }

    /**
     * Get blog content by ID for copying.
     * Returns the full content string or null if not found.
     */
    suspend fun getBlogContent(blogId: Long): String? {
        return try {
            blogRepository.getBlogById(blogId).getOrNull()?.content
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting blog content for id: $blogId", e)
            null
        }
    }

    /**
     * Get child prefix counts for popup drill-down.
     * Queries actual blog data to get real counts dynamically.
     */
    suspend fun getChildPrefixCounts(parentPrefix: String): Map<String, Int> {
        return try {
            blogRepository.getChildPrefixCounts(parentPrefix).getOrNull() ?: emptyMap()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting child prefix counts for: $parentPrefix", e)
            emptyMap()
        }
    }
}
