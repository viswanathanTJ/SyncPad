package com.example.largeindexblog.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.largeindexblog.data.index.PrefixIndexBuilder
import com.example.largeindexblog.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Repository for user preferences and settings.
 * Uses DataStore for persistent storage.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SettingsRepository"

        // Preference keys
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_FONT_SIZE = intPreferencesKey("font_size")
        private val KEY_MAX_DEPTH = intPreferencesKey("max_depth")
        private val KEY_SHOW_BOTTOM_INDEX = booleanPreferencesKey("show_bottom_index")

        // Default values
        const val DEFAULT_THEME = "system"
        const val DEFAULT_FONT_SIZE = 18
        val DEFAULT_MAX_DEPTH = PrefixIndexBuilder.DEFAULT_MAX_DEPTH
        const val DEFAULT_SHOW_BOTTOM_INDEX = false

        // Theme options
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        // Font size range
        const val MIN_FONT_SIZE = 12
        const val MAX_FONT_SIZE = 32
    }

    // ============================================
    // THEME
    // ============================================

    /**
     * Get the current theme setting.
     */
    fun getThemeFlow(): Flow<String> {
        return context.dataStore.data
            .map { preferences ->
                preferences[KEY_THEME] ?: DEFAULT_THEME
            }
            .catch { e ->
                AppLogger.e(TAG, "Error reading theme preference", e)
                emit(DEFAULT_THEME)
            }
    }

    /**
     * Set the theme.
     */
    suspend fun setTheme(theme: String): Result<Unit> {
        return try {
            context.dataStore.edit { preferences ->
                preferences[KEY_THEME] = theme
            }
            AppLogger.i(TAG, "Set theme to: $theme")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error setting theme", e)
            Result.failure(e)
        }
    }

    // ============================================
    // FONT SIZE
    // ============================================

    /**
     * Get the current font size.
     */
    fun getFontSizeFlow(): Flow<Int> {
        return context.dataStore.data
            .map { preferences ->
                preferences[KEY_FONT_SIZE] ?: DEFAULT_FONT_SIZE
            }
            .catch { e ->
                AppLogger.e(TAG, "Error reading font size preference", e)
                emit(DEFAULT_FONT_SIZE)
            }
    }

    /**
     * Set the font size.
     */
    suspend fun setFontSize(size: Int): Result<Unit> {
        return try {
            val clampedSize = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
            context.dataStore.edit { preferences ->
                preferences[KEY_FONT_SIZE] = clampedSize
            }
            AppLogger.i(TAG, "Set font size to: $clampedSize")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error setting font size", e)
            Result.failure(e)
        }
    }

    // ============================================
    // MAX DEPTH
    // ============================================

    /**
     * Get the current max depth for prefix index.
     */
    fun getMaxDepthFlow(): Flow<Int> {
        return context.dataStore.data
            .map { preferences ->
                preferences[KEY_MAX_DEPTH] ?: DEFAULT_MAX_DEPTH
            }
            .catch { e ->
                AppLogger.e(TAG, "Error reading max depth preference", e)
                emit(DEFAULT_MAX_DEPTH)
            }
    }

    /**
     * Set the max depth.
     */
    suspend fun setMaxDepth(depth: Int): Result<Unit> {
        return try {
            val clampedDepth = depth.coerceIn(1, 10)
            context.dataStore.edit { preferences ->
                preferences[KEY_MAX_DEPTH] = clampedDepth
            }
            AppLogger.i(TAG, "Set max depth to: $clampedDepth")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error setting max depth", e)
            Result.failure(e)
        }
    }

    // ============================================
    // BOTTOM INDEX
    // ============================================

    /**
     * Get whether to show bottom index.
     */
    fun getShowBottomIndexFlow(): Flow<Boolean> {
        return context.dataStore.data
            .map { preferences ->
                preferences[KEY_SHOW_BOTTOM_INDEX] ?: DEFAULT_SHOW_BOTTOM_INDEX
            }
            .catch { e ->
                AppLogger.e(TAG, "Error reading bottom index preference", e)
                emit(DEFAULT_SHOW_BOTTOM_INDEX)
            }
    }

    /**
     * Set whether to show bottom index.
     */
    suspend fun setShowBottomIndex(show: Boolean): Result<Unit> {
        return try {
            context.dataStore.edit { preferences ->
                preferences[KEY_SHOW_BOTTOM_INDEX] = show
            }
            AppLogger.i(TAG, "Set show bottom index to: $show")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error setting bottom index preference", e)
            Result.failure(e)
        }
    }

    // ============================================
    // ALL SETTINGS
    // ============================================

    /**
     * Data class for all settings.
     */
    data class AppSettings(
        val theme: String = DEFAULT_THEME,
        val fontSize: Int = DEFAULT_FONT_SIZE,
        val maxDepth: Int = DEFAULT_MAX_DEPTH,
        val showBottomIndex: Boolean = DEFAULT_SHOW_BOTTOM_INDEX
    )

    /**
     * Get all settings as a single flow.
     */
    fun getSettingsFlow(): Flow<AppSettings> {
        return context.dataStore.data
            .map { preferences ->
                AppSettings(
                    theme = preferences[KEY_THEME] ?: DEFAULT_THEME,
                    fontSize = preferences[KEY_FONT_SIZE] ?: DEFAULT_FONT_SIZE,
                    maxDepth = preferences[KEY_MAX_DEPTH] ?: DEFAULT_MAX_DEPTH,
                    showBottomIndex = preferences[KEY_SHOW_BOTTOM_INDEX] ?: DEFAULT_SHOW_BOTTOM_INDEX
                )
            }
            .catch { e ->
                AppLogger.e(TAG, "Error reading settings", e)
                emit(AppSettings())
            }
    }
}
