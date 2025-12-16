package com.example.largeindexblog.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.largeindexblog.data.dao.BlogDao
import com.example.largeindexblog.data.entity.BlogEntity
import com.example.largeindexblog.data.model.BlogListItem
import com.example.largeindexblog.data.paging.BlogPagingSource
import com.example.largeindexblog.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for blog operations.
 * Acts as the single source of truth for blog data.
 * Coordinates Room + Paging and handles all exceptions.
 */
@Singleton
class BlogRepository @Inject constructor(
    private val blogDao: BlogDao
) {
    companion object {
        private const val TAG = "BlogRepository"
        private const val PAGE_SIZE = 50
    }

    // ============================================
    // PAGING
    // ============================================

    /**
     * Get a paged flow of blogs for the list screen.
     * Uses cursor-based pagination for stability at 200k+ rows.
     * 
     * @param prefixFilter Optional filter for prefix-based navigation
     * @return Flow of PagingData containing BlogListItem (no content)
     */
    fun getPagedBlogs(prefixFilter: String? = null): Flow<PagingData<BlogListItem>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                prefetchDistance = PAGE_SIZE / 2
            ),
            pagingSourceFactory = { BlogPagingSource(blogDao, prefixFilter) }
        ).flow
            .catch { e ->
                AppLogger.e(TAG, "Error in paged blogs flow", e)
                throw e
            }
    }

    // ============================================
    // CRUD OPERATIONS
    // ============================================

    /**
     * Get a blog by ID.
     * Used for the detail screen - this is when content is loaded.
     */
    suspend fun getBlogById(id: Long): Result<BlogEntity?> {
        return withContext(Dispatchers.IO) {
            try {
                val blog = blogDao.getById(id)
                Result.success(blog)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting blog by id: $id", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get a blog by ID as a Flow for reactive updates.
     */
    fun getBlogByIdFlow(id: Long): Flow<BlogEntity?> {
        return blogDao.getByIdFlow(id)
            .flowOn(Dispatchers.IO)
            .catch { e ->
                AppLogger.e(TAG, "Error in blog flow for id: $id", e)
                throw e
            }
    }

    /**
     * Insert a new blog.
     * 
     * @param blog The blog entity to insert
     * @return Result containing the new blog's ID or an error
     */
    suspend fun insertBlog(blog: BlogEntity): Result<Long> {
        return withContext(Dispatchers.IO) {
            try {
                val id = blogDao.insert(blog)
                AppLogger.i(TAG, "Inserted blog with id: $id")
                Result.success(id)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error inserting blog", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Update an existing blog.
     */
    suspend fun updateBlog(blog: BlogEntity): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val rowsUpdated = blogDao.update(blog)
                AppLogger.i(TAG, "Updated blog id: ${blog.id}, rows: $rowsUpdated")
                Result.success(rowsUpdated)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error updating blog id: ${blog.id}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Delete a blog by ID.
     */
    suspend fun deleteBlog(id: Long): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val rowsDeleted = blogDao.deleteById(id)
                AppLogger.i(TAG, "Deleted blog id: $id, rows: $rowsDeleted")
                Result.success(rowsDeleted)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error deleting blog id: $id", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Delete all blogs.
     * Used during hard sync.
     */
    suspend fun deleteAllBlogs(): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val rowsDeleted = blogDao.deleteAll()
                AppLogger.i(TAG, "Deleted all blogs, rows: $rowsDeleted")
                Result.success(rowsDeleted)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error deleting all blogs", e)
                Result.failure(e)
            }
        }
    }

    // ============================================
    // UTILITY OPERATIONS
    // ============================================

    /**
     * Get the total count of blogs.
     */
    suspend fun getBlogCount(): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val count = blogDao.getCount()
                Result.success(count)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting blog count", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get the blog count as a Flow for reactive updates.
     */
    fun getBlogCountFlow(): Flow<Int> {
        return blogDao.getCountFlow()
            .flowOn(Dispatchers.IO)
            .catch { e ->
                AppLogger.e(TAG, "Error in blog count flow", e)
                throw e
            }
    }

    /**
     * Get the first blog ID for a given prefix.
     * Used for alphabet sidebar navigation.
     */
    suspend fun getFirstBlogIdByPrefix(prefix: String): Result<Long?> {
        return withContext(Dispatchers.IO) {
            try {
                val id = blogDao.getFirstIdByPrefix("$prefix%")
                Result.success(id)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting first blog id for prefix: $prefix", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get blogs that need to be synced.
     * Returns blogs created or updated after the given timestamp.
     */
    suspend fun getBlogsForSync(afterTimestamp: Long): Result<List<BlogEntity>> {
        return withContext(Dispatchers.IO) {
            try {
                val blogs = blogDao.getBlogsForSync(afterTimestamp)
                AppLogger.d(TAG, "Found ${blogs.size} blogs for sync after $afterTimestamp")
                Result.success(blogs)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting blogs for sync", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Insert multiple blogs at once.
     * Used during sync.
     */
    suspend fun insertBlogs(blogs: List<BlogEntity>): Result<List<Long>> {
        return withContext(Dispatchers.IO) {
            try {
                val ids = blogDao.insertAll(blogs)
                AppLogger.i(TAG, "Inserted ${ids.size} blogs")
                Result.success(ids)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error inserting blogs batch", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get the title prefix for a blog by ID.
     * Used for partial index updates.
     */
    suspend fun getTitlePrefixById(id: Long): Result<String?> {
        return withContext(Dispatchers.IO) {
            try {
                val prefix = blogDao.getTitlePrefixById(id)
                Result.success(prefix)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting title prefix for id: $id", e)
                Result.failure(e)
            }
        }
    }

    // ============================================
    // SEARCH OPERATIONS
    // ============================================

    /**
     * Search blogs by title only (fast, title-first search).
     */
    suspend fun searchByTitle(query: String, limit: Int = 50): Result<List<BlogListItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val results = blogDao.searchByTitle("%$query%", limit)
                AppLogger.d(TAG, "Title search '$query' found ${results.size} results")
                Result.success(results)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error searching by title: $query", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Search blogs by title or content (advanced search).
     */
    suspend fun searchByTitleOrContent(query: String, limit: Int = 50): Result<List<BlogListItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val results = blogDao.searchByTitleOrContent("%$query%", limit)
                AppLogger.d(TAG, "Content search '$query' found ${results.size} results")
                Result.success(results)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error searching by title/content: $query", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Advanced search with content and date filters.
     */
    suspend fun advancedSearch(
        query: String,
        includeContent: Boolean = false,
        createdAfter: Long = 0,
        createdBefore: Long = 0,
        updatedAfter: Long = 0,
        updatedBefore: Long = 0,
        limit: Int = 50
    ): Result<List<BlogListItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val results = blogDao.advancedSearch(
                    query = "%$query%",
                    includeContent = if (includeContent) 1 else 0,
                    createdAfter = createdAfter,
                    createdBefore = createdBefore,
                    updatedAfter = updatedAfter,
                    updatedBefore = updatedBefore,
                    limit = limit
                )
                AppLogger.d(TAG, "Advanced search '$query' found ${results.size} results")
                Result.success(results)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in advanced search: $query", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get child prefix counts for popup drill-down.
     * Queries actual blog data to get real counts.
     */
    suspend fun getChildPrefixCounts(
        parentPrefix: String
    ): Result<Map<String, Int>> {
        return withContext(Dispatchers.IO) {
            try {
                val childDepth = parentPrefix.length + 1
                val counts = blogDao.getChildPrefixCounts(
                    parentPrefix = parentPrefix.uppercase(),
                    parentLength = parentPrefix.length,
                    childDepth = childDepth
                )
                val countMap = counts.associate { it.prefix to it.count }
                AppLogger.d(TAG, "Got ${counts.size} child prefixes for '$parentPrefix'")
                Result.success(countMap)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting child prefix counts for: $parentPrefix", e)
                Result.failure(e)
            }
        }
    }
}
