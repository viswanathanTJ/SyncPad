package com.viswa2k.syncpad.ui.state

import com.viswa2k.syncpad.sync.SyncResult

/**
 * Sealed class representing UI states.
 * All ViewModels use this pattern for consistent error handling.
 */
sealed class UiState<out T> {
    /**
     * Initial loading state.
     */
    data object Loading : UiState<Nothing>()

    /**
     * Success state with data.
     */
    data class Success<T>(val data: T) : UiState<T>()

    /**
     * Error state with user-friendly message and optional exception for logging.
     */
    data class Error(
        val message: String,
        val exception: Throwable? = null
    ) : UiState<Nothing>()

    /**
     * Check if this is a loading state.
     */
    val isLoading: Boolean get() = this is Loading

    /**
     * Check if this is a success state.
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Check if this is an error state.
     */
    val isError: Boolean get() = this is Error

    /**
     * Get the data if in success state, null otherwise.
     */
    fun getOrNull(): T? = (this as? Success)?.data

    /**
     * Get the error message if in error state, null otherwise.
     */
    fun errorMessageOrNull(): String? = (this as? Error)?.message
}

/**
 * Sealed class for sync operation states.
 */
sealed class SyncState {
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data class Success(val result: SyncResult, val isManual: Boolean = true) : SyncState()
    data class Error(val message: String, val exception: Throwable? = null) : SyncState()
    
    val isSyncing: Boolean get() = this is Syncing
}

/**
 * Sealed class for index rebuild states.
 */
sealed class IndexState {
    data object Idle : IndexState()
    data object Building : IndexState()
    data class Complete(val entriesCount: Int) : IndexState()
    data class Error(val message: String, val exception: Throwable? = null) : IndexState()
}
