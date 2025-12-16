package com.example.largeindexblog.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.largeindexblog.data.entity.PrefixIndexEntity
import com.example.largeindexblog.data.model.BlogListItem
import com.example.largeindexblog.repository.BlogRepository
import com.example.largeindexblog.repository.PrefixIndexRepository
import com.example.largeindexblog.repository.SettingsRepository
import com.example.largeindexblog.sync.SyncManager
import com.example.largeindexblog.ui.state.IndexState
import com.example.largeindexblog.ui.state.SyncState
import com.example.largeindexblog.ui.state.UiState
import com.example.largeindexblog.util.AppLogger
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
    }

    // ============================================
    // ACTIONS
    // ============================================

    /**
     * Refresh the list by triggering a new paging load.
     */
    fun refreshList() {
        AppLogger.d(TAG, "Refreshing list")
        _refreshTrigger.value = _refreshTrigger.value + 1
        loadAlphabetIndex()
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
     * Perform sync from the home screen.
     */
    fun performSync() {
        viewModelScope.launch {
            try {
                _syncState.value = SyncState.Syncing
                
                val result = syncManager.performIncrementalSync()
                
                result.fold(
                    onSuccess = { syncResult ->
                        _syncState.value = SyncState.Success(syncResult)
                        // Refresh the list after sync
                        refreshList()
                    },
                    onFailure = { e ->
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
}
