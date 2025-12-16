package com.viswa2k.syncpad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.viswa2k.syncpad.repository.SettingsRepository
import com.viswa2k.syncpad.ui.navigation.AppNavigation
import com.viswa2k.syncpad.ui.theme.SyncPadTheme
import com.viswa2k.syncpad.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            enableEdgeToEdge()
            
            setContent {
                val themeSetting by settingsRepository.getThemeFlow()
                    .collectAsState(initial = SettingsRepository.DEFAULT_THEME)

                SyncPadTheme(themeSetting = themeSetting) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val navController = rememberNavController()
                        AppNavigation(navController = navController)
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
