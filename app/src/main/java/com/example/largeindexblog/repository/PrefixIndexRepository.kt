package com.example.largeindexblog.repository

import com.example.largeindexblog.data.dao.PrefixIndexDao
import com.example.largeindexblog.data.entity.PrefixIndexEntity
import com.example.largeindexblog.data.index.PrefixIndexBuilder
import com.example.largeindexblog.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for prefix index operations.
 * The prefix index is LOCAL ONLY and enables fast alphabet navigation.
 */
@Singleton
class PrefixIndexRepository @Inject constructor(
    private val prefixIndexDao: PrefixIndexDao,
    private val prefixIndexBuilder: PrefixIndexBuilder
) {
    companion object {
        private const val TAG = "PrefixIndexRepo"
    }

    // ============================================
    // INDEX BUILDING
    // ============================================

    /**
     * Perform a full rebuild of the prefix index.
     * Safe to run multiple times.
     */
    suspend fun rebuildIndex(maxDepth: Int = PrefixIndexBuilder.DEFAULT_MAX_DEPTH): Result<Int> {
        AppLogger.i(TAG, "Starting full index rebuild with maxDepth=$maxDepth")
        return prefixIndexBuilder.fullRebuild(maxDepth)
    }

    /**
     * Perform a partial update of the prefix index.
     * More efficient than full rebuild for small changes.
     */
    suspend fun partialUpdate(
        affectedPrefixes: Set<String>,
        maxDepth: Int = PrefixIndexBuilder.DEFAULT_MAX_DEPTH
    ): Result<Int> {
        AppLogger.d(TAG, "Starting partial index update for ${affectedPrefixes.size} prefixes")
        return prefixIndexBuilder.partialUpdate(affectedPrefixes, maxDepth)
    }

    /**
     * Clear the entire prefix index.
     */
    suspend fun clearIndex(): Result<Unit> {
        return prefixIndexBuilder.clearIndex()
    }

    // ============================================
    // QUERY OPERATIONS
    // ============================================

    /**
     * Get the alphabet index (depth 1 prefixes) for sidebar navigation.
     */
    suspend fun getAlphabetIndex(): Result<List<PrefixIndexEntity>> {
        return withContext(Dispatchers.IO) {
            try {
                val index = prefixIndexDao.getAlphabetIndex()
                Result.success(index)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting alphabet index", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get the alphabet index as a Flow for reactive updates.
     */
    fun getAlphabetIndexFlow(): Flow<List<PrefixIndexEntity>> {
        return prefixIndexDao.getAlphabetIndexFlow()
            .flowOn(Dispatchers.IO)
            .catch { e ->
                AppLogger.e(TAG, "Error in alphabet index flow", e)
                throw e
            }
    }

    /**
     * Get all prefix indices.
     */
    suspend fun getAllIndices(): Result<List<PrefixIndexEntity>> {
        return withContext(Dispatchers.IO) {
            try {
                val indices = prefixIndexDao.getAll()
                Result.success(indices)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting all indices", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get the first blog ID for a specific prefix.
     * Used for fast navigation.
     */
    suspend fun getFirstBlogId(prefix: String, depth: Int = 1): Result<Long?> {
        return withContext(Dispatchers.IO) {
            try {
                val id = prefixIndexDao.getFirstBlogId(prefix, depth)
                Result.success(id)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting first blog id for prefix: $prefix", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get sub-prefixes that start with a given prefix.
     * Used for hierarchical expansion.
     */
    suspend fun getSubPrefixes(prefix: String, currentDepth: Int): Result<List<PrefixIndexEntity>> {
        return withContext(Dispatchers.IO) {
            try {
                val subPrefixes = prefixIndexDao.getSubPrefixes("$prefix%", currentDepth)
                Result.success(subPrefixes)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting sub-prefixes for: $prefix", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Check if the index exists and has entries.
     */
    suspend fun hasIndex(): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val count = prefixIndexDao.getCount()
                Result.success(count > 0)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error checking if index exists", e)
                Result.failure(e)
            }
        }
    }
}
