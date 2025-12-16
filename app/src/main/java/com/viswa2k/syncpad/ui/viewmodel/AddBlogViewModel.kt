package com.viswa2k.syncpad.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viswa2k.syncpad.data.entity.BlogEntity
import com.viswa2k.syncpad.repository.BlogRepository
import com.viswa2k.syncpad.repository.PrefixIndexRepository
import com.viswa2k.syncpad.sync.SyncManager
import com.viswa2k.syncpad.ui.state.UiState
import com.viswa2k.syncpad.util.AppLogger
import android.os.Build
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for adding/editing blog posts.
 */
@HiltViewModel
class AddBlogViewModel @Inject constructor(
    private val blogRepository: BlogRepository,
    private val prefixIndexRepository: PrefixIndexRepository,
    private val syncManager: SyncManager
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
     * Triggers reindex and sync after save.
     */
    fun saveBlog(title: String, content: String) {
        viewModelScope.launch {
            try {
                // Don't save empty blogs
                if (title.isBlank() && content.isBlank()) {
                    AppLogger.d(TAG, "Skipping save - empty blog")
                    _saveState.value = UiState.Success(-1L) // Signal to navigate back
                    return@launch
                }

                // Use content first line as title if title is empty
                val finalTitle = if (title.isBlank()) {
                    generateTitleFromContent(content)
                } else {
                    title
                }

                if (finalTitle.isBlank()) {
                    _saveState.value = UiState.Error("Cannot save empty blog")
                    return@launch
                }

                _saveState.value = UiState.Loading
                
                val now = System.currentTimeMillis()
                val titlePrefix = BlogEntity.generateTitlePrefix(finalTitle, DEFAULT_MAX_DEPTH)
                
                val blog = currentBlog?.copy(
                    title = finalTitle,
                    content = content,
                    titlePrefix = titlePrefix,
                    updatedAt = now
                ) ?: BlogEntity(
                    title = finalTitle,
                    content = content,
                    titlePrefix = titlePrefix,
                    createdAt = now,
                    updatedAt = now,
                    deviceId = Build.MODEL
                )

                val result = if (currentBlog != null) {
                    blogRepository.updateBlog(blog).map { blog.id }
                } else {
                    blogRepository.insertBlog(blog)
                }

                result.fold(
                    onSuccess = { id ->
                        AppLogger.i(TAG, "Blog saved successfully: $id")
                        
                        // Trigger partial index update
                        try {
                            AppLogger.d(TAG, "Reindexing prefix: $titlePrefix")
                            prefixIndexRepository.partialUpdate(setOf(titlePrefix))
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Error reindexing after save", e)
                        }
                        
                        // Trigger sync to update cloud
                        try {
                            val syncResult = syncManager.performIncrementalSync()
                            syncResult.fold(
                                onSuccess = { result ->
                                    AppLogger.i(TAG, "Sync after save: ${result.toDisplayString()}")
                                },
                                onFailure = { e ->
                                    AppLogger.e(TAG, "Sync failed after save", e)
                                }
                            )
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Error syncing after save", e)
                        }
                        
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
     * Generate title from content - first line or first 200 chars.
     */
    private fun generateTitleFromContent(content: String): String {
        val trimmed = content.trim()
        val firstLine = trimmed.lines().firstOrNull()?.trim() ?: ""
        
        return if (firstLine.isNotBlank()) {
            cleanTitle(firstLine).take(200)
        } else {
            cleanTitle(trimmed).take(200)
        }
    }

    /**
     * Clean title by removing emojis and invalid characters.
     */
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("[\\p{So}\\p{Sk}]"), "")
            .replace(Regex("[\\p{Cc}\\p{Cf}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Reset save state after handling.
     */
    fun resetSaveState() {
        _saveState.value = null
    }
}
