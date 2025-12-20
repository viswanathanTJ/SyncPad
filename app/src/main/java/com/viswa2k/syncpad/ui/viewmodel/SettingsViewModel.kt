package com.viswa2k.syncpad.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viswa2k.syncpad.repository.BlogRepository
import com.viswa2k.syncpad.repository.PrefixIndexRepository
import com.viswa2k.syncpad.repository.SettingsRepository
import com.viswa2k.syncpad.repository.SyncRepository
import com.viswa2k.syncpad.sync.SyncManager
import com.viswa2k.syncpad.ui.state.IndexState
import com.viswa2k.syncpad.ui.state.SyncState
import com.viswa2k.syncpad.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the settings screen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val blogRepository: BlogRepository,
    private val prefixIndexRepository: PrefixIndexRepository,
    private val syncManager: SyncManager
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    // ============================================
    // SETTINGS STATE
    // ============================================

    val settings: StateFlow<SettingsRepository.AppSettings> = settingsRepository.getSettingsFlow()
        .catch { e ->
            AppLogger.e(TAG, "Error in settings flow", e)
            emit(SettingsRepository.AppSettings())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsRepository.AppSettings()
        )

    // ============================================
    // INFO STATE
    // ============================================

    data class InfoState(
        val localCount: Int = 0,
        val lastSyncTime: Long = 0
    )

    val infoState: StateFlow<InfoState> = combine(
        blogRepository.getBlogCountFlow(),
        syncRepository.getLastSyncTimeFlow()
    ) { count, lastSync ->
        InfoState(
            localCount = count,
            lastSyncTime = lastSync
        )
    }
        .catch { e ->
            AppLogger.e(TAG, "Error in info state flow", e)
            emit(InfoState())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = InfoState()
        )

    // ============================================
    // SYNC STATE
    // ============================================

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // ============================================
    // INDEX STATE
    // ============================================

    private val _indexState = MutableStateFlow<IndexState>(IndexState.Idle)
    val indexState: StateFlow<IndexState> = _indexState.asStateFlow()

    // ============================================
    // SETTINGS ACTIONS
    // ============================================

    fun setTheme(theme: String) {
        viewModelScope.launch {
            try {
                settingsRepository.setTheme(theme)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting theme", e)
            }
        }
    }

    fun setFontSize(size: Int) {
        viewModelScope.launch {
            try {
                settingsRepository.setFontSize(size)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting font size", e)
            }
        }
    }

    fun setMaxDepth(depth: Int) {
        viewModelScope.launch {
            try {
                settingsRepository.setMaxDepth(depth)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting max depth", e)
            }
        }
    }

    fun setShowBottomIndex(show: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setShowBottomIndex(show)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting show bottom index", e)
            }
        }
    }

    fun setShowSidebar(show: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setShowSidebar(show)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting show sidebar", e)
            }
        }
    }

    fun setShowQuickNavFab(show: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setShowQuickNavFab(show)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting show quick nav FAB", e)
            }
        }
    }

    // ============================================
    // SYNC ACTIONS
    // ============================================

    /**
     * Perform incremental sync.
     */
    fun performIncrementalSync() {
        viewModelScope.launch {
            try {
                _syncState.value = SyncState.Syncing()
                
                val result = syncManager.performIncrementalSync()
                
                result.fold(
                    onSuccess = { syncResult ->
                        _syncState.value = SyncState.Success(syncResult)
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Error in incremental sync", e)
                        _syncState.value = SyncState.Error(
                            message = "Sync failed: ${e.message}",
                            exception = e
                        )
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in performIncrementalSync", e)
                _syncState.value = SyncState.Error(
                    message = "Sync failed: ${e.message}",
                    exception = e
                )
            }
        }
    }

    /**
     * Perform hard sync (clear and rebuild).
     * Uses launchHardSync to run in app scope so it survives navigation.
     */
    fun performHardSync() {
        // Set syncing state immediately for UI feedback
        _syncState.value = SyncState.Syncing()
        
        // Launch in app scope via SyncManager (survives navigation)
        syncManager.launchHardSync()
        
        // Observe lastSyncResult for completion (handled in init or LaunchedEffect in UI)
    }

    fun resetSyncState() {
        _syncState.value = SyncState.Idle
    }

    // ============================================
    // INDEX ACTIONS
    // ============================================

    /**
     * Rebuild the prefix index.
     */
    fun rebuildIndex() {
        viewModelScope.launch {
            try {
                _indexState.value = IndexState.Building
                
                val maxDepth = settings.value.maxDepth
                val result = prefixIndexRepository.rebuildIndex(maxDepth)
                
                result.fold(
                    onSuccess = { count ->
                        _indexState.value = IndexState.Complete(count)
                    },
                    onFailure = { e ->
                        AppLogger.e(TAG, "Error rebuilding index", e)
                        _indexState.value = IndexState.Error(
                            message = "Index rebuild failed: ${e.message}",
                            exception = e
                        )
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in rebuildIndex", e)
                _indexState.value = IndexState.Error(
                    message = "Index rebuild failed: ${e.message}",
                    exception = e
                )
            }
        }
    }

    fun resetIndexState() {
        _indexState.value = IndexState.Idle
    }
}
