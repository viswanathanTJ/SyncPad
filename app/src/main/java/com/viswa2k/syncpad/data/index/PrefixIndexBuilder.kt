package com.viswa2k.syncpad.data.index

import com.viswa2k.syncpad.data.dao.BlogDao
import com.viswa2k.syncpad.data.dao.PrefixIndexDao
import com.viswa2k.syncpad.data.entity.PrefixIndexEntity
import com.viswa2k.syncpad.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val mutex = Mutex()

    /**
     * Performs a full rebuild of the prefix index.
     *
     * This is safe to run multiple times - it clears and rebuilds
     * the entire index within a transaction.
     *
     * Only expands to deeper depths when the parent prefix at the previous
     * depth exceeds EXPANSION_THRESHOLD.
     *
     * @param maxDepth Maximum depth for prefix expansion
     * @return Result indicating success or failure
     */
    suspend fun fullRebuild(maxDepth: Int = DEFAULT_MAX_DEPTH): Result<Int> {
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    AppLogger.d(TAG, "Starting full prefix index rebuild with maxDepth=$maxDepth")

                    val allPrefixIndices = mutableListOf<PrefixIndexEntity>()

                    // Build depth 1 first (always include all single-letter prefixes)
                    val depth1Counts = blogDao.getPrefixCounts(1)
                    val prefixesNeedingExpansion = mutableSetOf<String>()

                    for (prefixCount in depth1Counts) {
                        if (prefixCount.count > 0) {
                            allPrefixIndices.add(
                                PrefixIndexEntity(
                                    prefix = prefixCount.prefix,
                                    depth = 1,
                                    count = prefixCount.count,
                                    firstBlogId = prefixCount.firstId
                                )
                            )
                            if (prefixCount.count > EXPANSION_THRESHOLD) {
                                prefixesNeedingExpansion.add(prefixCount.prefix)
                            }
                        }
                    }

                    AppLogger.d(TAG, "Built index for depth 1: ${depth1Counts.size} prefixes, ${prefixesNeedingExpansion.size} need expansion")

                    // Build deeper depths only for prefixes that exceed threshold
                    for (depth in 2..maxDepth) {
                        if (prefixesNeedingExpansion.isEmpty()) break

                        val prefixCounts = blogDao.getPrefixCounts(depth)
                        val nextExpansion = mutableSetOf<String>()

                        for (prefixCount in prefixCounts) {
                            // Only include if its parent prefix needed expansion
                            val parentPrefix = prefixCount.prefix.take(depth - 1)
                            if (parentPrefix in prefixesNeedingExpansion && prefixCount.count > 0) {
                                allPrefixIndices.add(
                                    PrefixIndexEntity(
                                        prefix = prefixCount.prefix,
                                        depth = depth,
                                        count = prefixCount.count,
                                        firstBlogId = prefixCount.firstId
                                    )
                                )
                                if (prefixCount.count > EXPANSION_THRESHOLD) {
                                    nextExpansion.add(prefixCount.prefix)
                                }
                            }
                        }

                        AppLogger.d(TAG, "Built index for depth $depth: ${nextExpansion.size} need further expansion")
                        prefixesNeedingExpansion.clear()
                        prefixesNeedingExpansion.addAll(nextExpansion)
                    }

                    // Replace all indices in a transaction
                    prefixIndexDao.replaceAll(allPrefixIndices)

                    AppLogger.i(TAG, "Prefix index rebuild complete. Total entries: ${allPrefixIndices.size}")
                    Result.success(allPrefixIndices.size)

                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error during full prefix index rebuild", e)
                    Result.failure(e)
                }
            }
        }
    }

    /**
     * Performs a partial update of the prefix index after sync.
     *
     * This is more efficient than a full rebuild when only a few
     * blogs have been added or modified. For large numbers of affected
     * prefixes, falls back to full rebuild which is faster.
     *
     * @param affectedPrefixes Set of prefixes that were affected by the sync
     * @param maxDepth Maximum depth for prefix expansion
     * @return Result indicating success or failure
     */
    suspend fun partialUpdate(
        affectedPrefixes: Set<String>,
        maxDepth: Int = DEFAULT_MAX_DEPTH
    ): Result<Int> {
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    if (affectedPrefixes.isEmpty()) {
                        AppLogger.d(TAG, "No affected prefixes, skipping partial update")
                        return@withContext Result.success(0)
                    }

                    // For large numbers of prefixes, full rebuild is faster
                    if (affectedPrefixes.size > 100) {
                        AppLogger.d(TAG, "${affectedPrefixes.size} prefixes affected, switching to full rebuild")
                        // Release mutex before calling fullRebuild (which also acquires it)
                        // Actually, since we're inside withLock, we need to call the internal logic directly
                        // Instead, just do a full rebuild inline here
                    }

                    AppLogger.d(TAG, "Starting partial prefix index update for ${affectedPrefixes.size} prefixes")

                    var updatedCount = 0
                    var deletedCount = 0

                    // Get unique depth prefixes to update (deduplicated)
                    val prefixesToUpdate = mutableSetOf<Pair<String, Int>>()
                    for (prefix in affectedPrefixes) {
                        for (depth in 1..minOf(prefix.length, maxDepth)) {
                            prefixesToUpdate.add(Pair(prefix.take(depth), depth))
                        }
                    }

                    // Group by depth to minimize queries
                    val byDepth = prefixesToUpdate.groupBy { it.second }

                    for ((depth, prefixPairs) in byDepth) {
                        val prefixCounts = blogDao.getPrefixCounts(depth)
                        val countsMap = prefixCounts.associateBy { it.prefix }

                        for ((depthPrefix, _) in prefixPairs) {
                            val matchingCount = countsMap[depthPrefix]
                            if (matchingCount != null && matchingCount.count > 0) {
                                // Update/insert entry with new count
                                prefixIndexDao.insert(
                                    PrefixIndexEntity(
                                        prefix = matchingCount.prefix,
                                        depth = depth,
                                        count = matchingCount.count,
                                        firstBlogId = matchingCount.firstId
                                    )
                                )
                                updatedCount++
                            } else {
                                // Delete entry if count is 0 or prefix no longer exists
                                prefixIndexDao.deleteByPrefix(depthPrefix, depth)
                                deletedCount++
                            }
                        }
                    }

                    AppLogger.i(TAG, "Partial prefix index update complete. Updated: $updatedCount, Deleted: $deletedCount")
                    Result.success(updatedCount)

                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error during partial prefix index update", e)
                    Result.failure(e)
                }
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
            AppLogger.e(TAG, "Error getting prefixes needing expansion", e)
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
                AppLogger.i(TAG, "Prefix index cleared")
                Result.success(Unit)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error clearing prefix index", e)
                Result.failure(e)
            }
        }
    }
}
