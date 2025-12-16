package com.example.largeindexblog.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.largeindexblog.data.entity.BlogEntity
import com.example.largeindexblog.repository.BlogRepository
import com.example.largeindexblog.repository.PrefixIndexRepository
import com.example.largeindexblog.ui.state.UiState
import com.example.largeindexblog.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for adding/editing blog posts.
 */
@HiltViewModel
class AddBlogViewModel @Inject constructor(
    private val blogRepository: BlogRepository,
    private val prefixIndexRepository: PrefixIndexRepository
) : ViewModel() {

    companion object {
        private const val TAG = "AddBlogViewModel"
        private const val DEFAULT_MAX_DEPTH = 5
    }

    // ============================================
    // STATE
    // ============================================

    private val _saveState = MutableStateFlow<UiState<Long>?>(null)
    val saveState: StateFlow<UiState<Long>?> = _saveState.asStateFlow()

    private val _loadState = MutableStateFlow<UiState<BlogEntity>?>(null)
    val loadState: StateFlow<UiState<BlogEntity>?> = _loadState.asStateFlow()

    // Current blog being edited (null for new)
    private var currentBlog: BlogEntity? = null

    // ============================================
    // ACTIONS
    // ============================================

    /**
     * Load an existing blog for editing.
     */
    fun loadBlog(blogId: Long) {
        viewModelScope.launch {
            try {
                _loadState.value = UiState.Loading
                
                val result = blogRepository.getBlogById(blogId)
                
                result.fold(
                    onSuccess = { blog ->
                        if (blog != null) {
                            currentBlog = blog
                            _loadState.value = UiState.Success(blog)
                        } else {
                            _loadState.value = UiState.Error("Blog not found")
                        }
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Error loading blog for edit: $blogId", e)
                        _loadState.value = UiState.Error(
                            message = "Failed to load blog: ${e.message}",
                            exception = e
                        )
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in loadBlog", e)
                _loadState.value = UiState.Error(
                    message = "Failed to load blog: ${e.message}",
                    exception = e
                )
            }
        }
    }

    /**
     * Save a new or updated blog.
     */
    fun saveBlog(title: String, content: String) {
        viewModelScope.launch {
            try {
                if (title.isBlank()) {
                    _saveState.value = UiState.Error("Title cannot be empty")
                    return@launch
                }

                _saveState.value = UiState.Loading
                
                val now = System.currentTimeMillis()
                val titlePrefix = BlogEntity.generateTitlePrefix(title, DEFAULT_MAX_DEPTH)
                
                val blog = currentBlog?.copy(
                    title = title,
                    content = content,
                    titlePrefix = titlePrefix,
                    updatedAt = now
                ) ?: BlogEntity(
                    title = title,
                    content = content,
                    titlePrefix = titlePrefix,
                    createdAt = now,
                    updatedAt = now,
                    deviceId = UUID.randomUUID().toString().take(8)
                )

                val result = if (currentBlog != null) {
                    blogRepository.updateBlog(blog).map { blog.id }
                } else {
                    blogRepository.insertBlog(blog)
                }

                result.fold(
                    onSuccess = { id ->
                        // Trigger partial index update
                        prefixIndexRepository.partialUpdate(setOf(titlePrefix))
                        _saveState.value = UiState.Success(id)
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Error saving blog", e)
                        _saveState.value = UiState.Error(
                            message = "Failed to save blog: ${e.message}",
                            exception = e
                        )
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in saveBlog", e)
                _saveState.value = UiState.Error(
                    message = "Failed to save blog: ${e.message}",
                    exception = e
                )
            }
        }
    }

    /**
     * Reset save state after handling.
     */
    fun resetSaveState() {
        _saveState.value = null
    }
}
