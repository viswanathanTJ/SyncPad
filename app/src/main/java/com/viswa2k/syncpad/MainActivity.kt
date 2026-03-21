package com.viswa2k.syncpad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.viswa2k.syncpad.data.update.AppUpdateManager
import com.viswa2k.syncpad.repository.SettingsRepository
import com.viswa2k.syncpad.sync.SyncManager
import com.viswa2k.syncpad.ui.components.UpdateDialog
import com.viswa2k.syncpad.ui.navigation.AppNavigation
import com.viswa2k.syncpad.ui.theme.SyncPadTheme
import com.viswa2k.syncpad.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Main activity for SyncPad.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var appUpdateManager: AppUpdateManager

    @Inject
    lateinit var syncManager: SyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            enableEdgeToEdge()
            
            setContent {
                val themeSetting by settingsRepository.getThemeFlow()
                    .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_THEME)

                SyncPadTheme(themeSetting = themeSetting) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val navController = rememberNavController()
                            val autoCheckUpdates by settingsRepository.getAutoCheckUpdatesFlow()
                                .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_AUTO_CHECK_UPDATES)

                            LaunchedEffect(Unit) {
                                if (syncManager.isSyncConfigured() && !syncManager.isSyncCurrentlyRunning()) {
                                    delay(1000)
                                    AppLogger.i(TAG, "Launching incremental sync on app open")
                                    syncManager.launchIncrementalSync()
                                }
                            }

                            LaunchedEffect(autoCheckUpdates) {
                                if (autoCheckUpdates) {
                                    appUpdateManager.checkForUpdateIfNeeded()
                                }
                            }

                            AppNavigation(navController = navController)
                            UpdateDialog(updateManager = appUpdateManager)
                        }
                    }
                }
            }
            
            AppLogger.i(TAG, "MainActivity created successfully")
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error in onCreate", e)
            // Rethrow to let the system handle the crash
            throw e
        }
    }
}
