package com.viswa2k.syncpad

import android.app.Application
import com.viswa2k.syncpad.util.AppLogger
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for SyncPad.
 * Annotated with @HiltAndroidApp for dependency injection.
 */
@HiltAndroidApp
class SyncPadApp : Application() {

    companion object {
        private const val TAG = "SyncPadApp"
    }

    override fun onCreate() {
        super.onCreate()
        
        AppLogger.i(TAG, "Application started")
        
        // Check if secrets are configured
        checkSecrets()
    }

    private fun checkSecrets() {
        // Log warnings for missing secrets
        // The app will still work without them, but sync features will be limited
        if (BuildConfig.SYNC_API_KEY.isEmpty()) {
            AppLogger.logSecretsMissing("SYNC_API_KEY")
        }
        if (BuildConfig.SYNC_BASE_URL.isEmpty()) {
            AppLogger.logSecretsMissing("SYNC_BASE_URL")
        }
    }
}
