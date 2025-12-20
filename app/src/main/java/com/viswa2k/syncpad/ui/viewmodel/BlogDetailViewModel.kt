package com.viswa2k.syncpad.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viswa2k.syncpad.data.entity.BlogEntity
import com.viswa2k.syncpad.repository.BlogRepository
import com.viswa2k.syncpad.repository.PrefixIndexRepository
import com.viswa2k.syncpad.sync.SyncManager
import com.viswa2k.syncpad.ui.state.UiState
import com.viswa2k.syncpad.util.AppLogger
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
    private val prefixIndexRepository: PrefixIndexRepository,
    private val syncManager: SyncManager,
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

    // Store the blog's prefix for reindexing after delete
    private var blogPrefix: String? = null

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
                            blogPrefix = blog.titlePrefix // Store for reindexing
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
     * Flow: 
     * 1. Backup blog data
     * 2. Delete locally + reindex (fast)
     * 3. Navigate back
     * 4. Try server delete in background
     * 5. If server fails, RESTORE local data and show error
     */
    fun deleteBlog() {
        viewModelScope.launch {
            try {
                _deleteState.value = UiState.Loading
                
                // STEP 1: Get the blog data for backup BEFORE deleting
                val blogResult = blogRepository.getBlogById(blogId)
                val blogBackup = blogResult.getOrNull()
                
                if (blogBackup == null) {
                    _deleteState.value = UiState.Error(
                        message = "Blog not found",
                        exception = null
                    )
                    return@launch
                }
                
                // STEP 2: Soft delete locally FIRST (fast UI response)
                // This sets is_deleted = true, which will be synced to server
                val localResult = blogRepository.softDeleteBlog(blogId)
                
                localResult.fold(
                    onSuccess = {
                        AppLogger.i(TAG, "Blog soft deleted locally: $blogId")
                        
                        // STEP 3: Reindex the affected prefix
                        blogPrefix?.let { prefix ->
                            try {
                                AppLogger.d(TAG, "Reindexing prefix: $prefix")
                                prefixIndexRepository.partialUpdate(setOf(prefix))
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Error reindexing after delete", e)
                            }
                        }
                        
                        // STEP 4: Navigate back immediately (fast UX)
                        _deleteState.value = UiState.Success(Unit)
                        
                        // STEP 5: Try server delete in background
                        // Use syncScope (application scope) to survive ViewModel destruction
                        syncManager.launchServerDelete(blogId, blogBackup) { error ->
                            // This callback is called on failure - restore local
                            if (error != null) {
                                viewModelScope.launch {
                                    AppLogger.e(TAG, "Server delete failed, restoring local: $blogId", error)
                                    
                                    // Restore the blog locally
                                    blogRepository.insertBlogSilent(blogBackup)
                                    
                                    // Reindex to restore the prefix
                                    blogPrefix?.let { prefix ->
                                        try {
                                            prefixIndexRepository.partialUpdate(setOf(prefix))
                                        } catch (e: Exception) {
                                            AppLogger.e(TAG, "Error reindexing after restore", e)
                                        }
                                    }
                                    
                                    // Show error (won't work if user navigated away, but try anyway)
                                    _deleteState.value = UiState.Error(
                                        message = "Delete failed, restored: ${error.message}",
                                        exception = error
                                    )
                                }
                            }
                        }
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Error deleting blog locally: $blogId", e)
                        _deleteState.value = UiState.Error(
                            message = "Failed to delete: ${e.message}",
                            exception = e
                        )
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in deleteBlog", e)
                _deleteState.value = UiState.Error(
                    message = "Failed to delete: ${e.message}",
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
