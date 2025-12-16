package com.viswa2k.syncpad.util

import android.util.Log

/**
 * Centralized logging utility for consistent log formatting.
 * All logging should go through this class.
 * 
 * RULES:
 * - Always log full stacktrace with Log.e
 * - Use consistent tags
 * - Never silently ignore errors
 */
object AppLogger {
    private const val APP_TAG = "SyncPad"

    /**
     * Log a debug message.
     */
    fun d(tag: String, message: String) {
        Log.d("$APP_TAG:$tag", message)
    }

    /**
     * Log an info message.
     */
    fun i(tag: String, message: String) {
        Log.i("$APP_TAG:$tag", message)
    }

    /**
     * Log a warning message.
     */
    fun w(tag: String, message: String) {
        Log.w("$APP_TAG:$tag", message)
    }

    /**
     * Log a warning with exception.
     */
    fun w(tag: String, message: String, throwable: Throwable) {
        Log.w("$APP_TAG:$tag", message, throwable)
    }

    /**
     * Log an error with full stacktrace.
     * This MUST be used for all caught exceptions.
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e("$APP_TAG:$tag", message, throwable)
        // Log full stacktrace
        Log.e("$APP_TAG:$tag", "Stacktrace: ${throwable.stackTraceToString()}")
    }

    /**
     * Log an error message without exception.
     */
    fun e(tag: String, message: String) {
        Log.e("$APP_TAG:$tag", message)
    }

    /**
     * Log secrets warning during app startup.
     * Call this to warn about missing secrets without exposing sensitive data.
     */
    fun logSecretsMissing(secretName: String) {
        w("Config", "Secret '$secretName' is not configured in local.properties. " +
                "Some features may be limited. See local.properties.example for setup instructions.")
    }
}
