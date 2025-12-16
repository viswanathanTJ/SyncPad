package com.viswa2k.syncpad.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viswa2k.syncpad.data.model.BlogListItem
import com.viswa2k.syncpad.repository.BlogRepository
import com.viswa2k.syncpad.ui.screen.SearchFilters
import com.viswa2k.syncpad.ui.state.UiState
import com.viswa2k.syncpad.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the search screen.
 * Handles search with debounce and advanced filters.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val blogRepository: BlogRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SearchViewModel"
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    // ============================================
    // STATE
    // ============================================

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchFilters = MutableStateFlow(SearchFilters())
    val searchFilters: StateFlow<SearchFilters> = _searchFilters.asStateFlow()

    private val _searchState = MutableStateFlow<UiState<List<BlogListItem>>>(
        UiState.Success(emptyList())
    )
    val searchState: StateFlow<UiState<List<BlogListItem>>> = _searchState.asStateFlow()

    init {
        observeSearchQuery()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchQuery() {
        viewModelScope.launch {
            _searchQuery
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { query ->
                    performSearch(query)
                }
        }
    }

    // ============================================
    // ACTIONS
    // ============================================

    /**
     * Set the search query.
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Set search filters.
     */
    fun setFilters(filters: SearchFilters) {
        _searchFilters.value = filters
        // Re-run search with new filters
        performSearch(_searchQuery.value)
    }

    /**
     * Clear the search.
     */
    fun clearSearch() {
        _searchQuery.value = ""
        _searchState.value = UiState.Success(emptyList())
    }

    /**
     * Clear all filters.
     */
    fun clearFilters() {
        _searchFilters.value = SearchFilters()
        performSearch(_searchQuery.value)
    }

    /**
     * Perform the search based on current query and filters.
     */
    private fun performSearch(query: String) {
        if (query.isBlank()) {
            _searchState.value = UiState.Success(emptyList())
            return
        }

        viewModelScope.launch {
            try {
                _searchState.value = UiState.Loading
                
                val filters = _searchFilters.value
                
                val result = if (filters.hasActiveFilters()) {
                    // Use advanced search with filters
                    blogRepository.advancedSearch(
                        query = query,
                        includeContent = filters.includeContent,
                        createdAfter = filters.createdAfter,
                        createdBefore = filters.createdBefore,
                        updatedAfter = filters.updatedAfter,
                        updatedBefore = filters.updatedBefore
                    )
                } else {
                    // Title-only search (fast)
                    blogRepository.searchByTitle(query)
                }
                
                result.fold(
                    onSuccess = { results ->
                        _searchState.value = UiState.Success(results)
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Search failed: $query", e)
                        _searchState.value = UiState.Error(
                            message = "Search failed: ${e.message}",
                            exception = e
                        )
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in search", e)
                _searchState.value = UiState.Error(
                    message = "Search error: ${e.message}",
                    exception = e
                )
            }
        }
    }

    /**
     * Get blog content for copying.
     */
    suspend fun getBlogContent(blogId: Long): String? {
        return try {
            blogRepository.getBlogById(blogId).getOrNull()?.content
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting blog content: $blogId", e)
            null
        }
    }
}
