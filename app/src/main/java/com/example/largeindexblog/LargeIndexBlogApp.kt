package com.example.largeindexblog

import android.app.Application
import com.example.largeindexblog.util.AppLogger
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for LargeIndexBlog.
 * Annotated with @HiltAndroidApp for dependency injection.
 */
@HiltAndroidApp
class LargeIndexBlogApp : Application() {

    companion object {
        private const val TAG = "LargeIndexBlogApp"
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
