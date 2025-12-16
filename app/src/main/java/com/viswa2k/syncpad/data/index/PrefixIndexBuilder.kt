package com.viswa2k.syncpad.data.index

import android.util.Log
import com.viswa2k.syncpad.data.dao.BlogDao
import com.viswa2k.syncpad.data.dao.PrefixIndexDao
import com.viswa2k.syncpad.data.entity.PrefixIndexEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builder for the prefix index.
 * 
 * Rules:
 * - title_prefix = UPPER(SUBSTR(title, 1, MAX_DEPTH))
 * - prefix_index is derived data (LOCAL ONLY, not synced)
 * - Depth increases only if count > EXPANSION_THRESHOLD (50)
 * - MAX_DEPTH is the upper bound for prefix length
 * 
 * The prefix index enables fast alphabet navigation and supports
 * hierarchical expansion for densely populated prefixes.
 */
@Singleton
class PrefixIndexBuilder @Inject constructor(
    private val blogDao: BlogDao,
    private val prefixIndexDao: PrefixIndexDao
) {
    companion object {
        private const val TAG = "PrefixIndexBuilder"
        
        /**
         * If a prefix has more than this many items,
         * we expand to the next depth level.
         */
        const val EXPANSION_THRESHOLD = 50
        
        /**
         * Default maximum depth for prefix generation.
         */
        const val DEFAULT_MAX_DEPTH = 5
    }

    /**
     * Performs a full rebuild of the prefix index.
     * 
     * This is safe to run multiple times - it clears and rebuilds
     * the entire index within a transaction.
     * 
     * @param maxDepth Maximum depth for prefix expansion
     * @return Result indicating success or failure
     */
    suspend fun fullRebuild(maxDepth: Int = DEFAULT_MAX_DEPTH): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting full prefix index rebuild with maxDepth=$maxDepth")
                
                val allPrefixIndices = mutableListOf<PrefixIndexEntity>()
                
                // Build indices for each depth level
                for (depth in 1..maxDepth) {
                    val prefixCounts = blogDao.getPrefixCounts(depth)
                    
                    for (prefixCount in prefixCounts) {
                        // Only add to index if:
                        // - Depth == 1 (always include single letter)
                        // - OR previous depth had > threshold items AND current has items
                        if (depth == 1 || prefixCount.count > 0) {
                            allPrefixIndices.add(
                                PrefixIndexEntity(
                                    prefix = prefixCount.prefix,
                                    depth = depth,
                                    count = prefixCount.count,
                                    firstBlogId = prefixCount.firstId
                                )
                            )
                        }
                    }
                    
                    Log.d(TAG, "Built index for depth $depth: ${prefixCounts.size} prefixes")
                }
                
                // Replace all indices in a transaction
                prefixIndexDao.replaceAll(allPrefixIndices)
                
                Log.i(TAG, "Prefix index rebuild complete. Total entries: ${allPrefixIndices.size}")
                Result.success(allPrefixIndices.size)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during full prefix index rebuild", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Performs a partial update of the prefix index after sync.
     * 
     * This is more efficient than a full rebuild when only a few
     * blogs have been added or modified.
     * 
     * @param affectedPrefixes Set of prefixes that were affected by the sync
     * @param maxDepth Maximum depth for prefix expansion
     * @return Result indicating success or failure
     */
    suspend fun partialUpdate(
        affectedPrefixes: Set<String>,
        maxDepth: Int = DEFAULT_MAX_DEPTH
    ): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                if (affectedPrefixes.isEmpty()) {
                    Log.d(TAG, "No affected prefixes, skipping partial update")
                    return@withContext Result.success(0)
                }
                
                Log.d(TAG, "Starting partial prefix index update for ${affectedPrefixes.size} prefixes")
                
                var updatedCount = 0
                
                for (prefix in affectedPrefixes) {
                    // Rebuild all depths for affected prefix
                    for (depth in 1..minOf(prefix.length, maxDepth)) {
                        val depthPrefix = prefix.take(depth)
                        val prefixCounts = blogDao.getPrefixCounts(depth)
                        
                        val matchingCount = prefixCounts.find { it.prefix == depthPrefix }
                        
                        if (matchingCount != null) {
                            prefixIndexDao.insert(
                                PrefixIndexEntity(
                                    prefix = matchingCount.prefix,
                                    depth = depth,
                                    count = matchingCount.count,
                                    firstBlogId = matchingCount.firstId
                                )
                            )
                            updatedCount++
                        }
                    }
                }
                
                Log.i(TAG, "Partial prefix index update complete. Updated entries: $updatedCount")
                Result.success(updatedCount)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during partial prefix index update", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Gets prefixes that should be expanded (have more than threshold items).
     * 
     * @param depth Current depth to check
     * @return List of prefixes that need expansion
     */
    suspend fun getPrefixesNeedingExpansion(depth: Int): List<String> {
        return try {
            prefixIndexDao.getByDepth(depth)
                .filter { it.count > EXPANSION_THRESHOLD }
                .map { it.prefix }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting prefixes needing expansion", e)
            emptyList()
        }
    }

    /**
     * Clears the entire prefix index.
     * Useful before a hard sync.
     */
    suspend fun clearIndex(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                prefixIndexDao.deleteAll()
                Log.i(TAG, "Prefix index cleared")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing prefix index", e)
                Result.failure(e)
            }
        }
    }
}
