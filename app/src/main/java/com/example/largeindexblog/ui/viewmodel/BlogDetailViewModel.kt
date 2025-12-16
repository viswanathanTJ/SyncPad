package com.example.largeindexblog.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.largeindexblog.data.entity.BlogEntity
import com.example.largeindexblog.repository.BlogRepository
import com.example.largeindexblog.ui.state.UiState
import com.example.largeindexblog.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the blog detail screen.
 * Loads blog content by ID only (content is never loaded in list).
 */
@HiltViewModel
class BlogDetailViewModel @Inject constructor(
    private val blogRepository: BlogRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "BlogDetailViewModel"
        const val ARG_BLOG_ID = "blogId"
    }

    // ============================================
    // STATE
    // ============================================

    private val _blogState = MutableStateFlow<UiState<BlogEntity>>(UiState.Loading)
    val blogState: StateFlow<UiState<BlogEntity>> = _blogState.asStateFlow()

    private val _deleteState = MutableStateFlow<UiState<Unit>?>(null)
    val deleteState: StateFlow<UiState<Unit>?> = _deleteState.asStateFlow()

    // Get blog ID from navigation arguments
    private val blogId: Long = savedStateHandle.get<Long>(ARG_BLOG_ID) ?: -1L

    init {
        if (blogId > 0) {
            loadBlog()
        } else {
            _blogState.value = UiState.Error("Invalid blog ID")
        }
    }

    // ============================================
    // ACTIONS
    // ============================================

    /**
     * Load the blog by ID.
     * This is when content is actually loaded.
     */
    fun loadBlog() {
        viewModelScope.launch {
            try {
                _blogState.value = UiState.Loading
                
                val result = blogRepository.getBlogById(blogId)
                
                result.fold(
                    onSuccess = { blog ->
                        if (blog != null) {
                            _blogState.value = UiState.Success(blog)
                        } else {
                            _blogState.value = UiState.Error("Blog not found")
                        }
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Error loading blog id: $blogId", e)
                        _blogState.value = UiState.Error(
                            message = "Failed to load blog: ${e.message}",
                            exception = e
                        )
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in loadBlog", e)
                _blogState.value = UiState.Error(
                    message = "Failed to load blog: ${e.message}",
                    exception = e
                )
            }
        }
    }

    /**
     * Delete the current blog.
     */
    fun deleteBlog() {
        viewModelScope.launch {
            try {
                _deleteState.value = UiState.Loading
                
                val result = blogRepository.deleteBlog(blogId)
                
                result.fold(
                    onSuccess = {
                        _deleteState.value = UiState.Success(Unit)
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Error deleting blog id: $blogId", e)
                        _deleteState.value = UiState.Error(
                            message = "Failed to delete blog: ${e.message}",
                            exception = e
                        )
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in deleteBlog", e)
                _deleteState.value = UiState.Error(
                    message = "Failed to delete blog: ${e.message}",
                    exception = e
                )
            }
        }
    }

    /**
     * Reset delete state after handling.
     */
    fun resetDeleteState() {
        _deleteState.value = null
    }
}
