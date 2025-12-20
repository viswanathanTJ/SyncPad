package com.viswa2k.syncpad.data.paging

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.viswa2k.syncpad.data.dao.BlogDao
import com.viswa2k.syncpad.data.model.BlogListItem

/**
 * Sort order for blog list.
 */
enum class SortOrder {
    ALPHABETICAL,
    LATEST
}

/**
 * Cursor-based PagingSource for blogs.
 * 
 * IMPORTANT:
 * - NEVER uses OFFSET for stable paging at 200k+ rows
 * - Only loads id, title, created_at (NEVER content)
 * - Uses appropriate cursor for sort order
 */
class BlogPagingSource(
    private val blogDao: BlogDao,
    private val prefixFilter: String? = null,
    private val sortOrder: SortOrder = SortOrder.ALPHABETICAL
) : PagingSource<BlogCursor, BlogListItem>() {

    companion object {
        private const val TAG = "BlogPagingSource"
        private const val PAGE_SIZE = 50
    }

    override fun getRefreshKey(state: PagingState<BlogCursor, BlogListItem>): BlogCursor? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestItemToPosition(anchorPosition)?.let { item ->
                BlogCursor(
                    titlePrefix = item.title.take(10).uppercase(),
                    title = item.title,
                    id = item.id,
                    updatedAt = item.updatedAt
                )
            }
        }
    }

    override suspend fun load(params: LoadParams<BlogCursor>): LoadResult<BlogCursor, BlogListItem> {
        return try {
            val cursor = params.key
            val pageSize = params.loadSize.coerceAtMost(PAGE_SIZE)

            val items = when {
                // Prefix filter always uses alphabetical
                prefixFilter != null -> {
                    if (cursor == null) {
                        blogDao.getByPrefixPattern("$prefixFilter%", pageSize)
                    } else {
                        blogDao.getNextPage(
                            cursorPrefix = cursor.titlePrefix,
                            cursorTitle = cursor.title,
                            cursorId = cursor.id,
                            pageSize = pageSize
                        )
                    }
                }
                // Sort by latest
                sortOrder == SortOrder.LATEST -> {
                    if (cursor == null) {
                        blogDao.getFirstPageByLatest(pageSize)
                    } else {
                        blogDao.getNextPageByLatest(
                            cursorUpdatedAt = cursor.updatedAt,
                            cursorId = cursor.id,
                            pageSize = pageSize
                        )
                    }
                }
                // Default: alphabetical
                else -> {
                    if (cursor == null) {
                        blogDao.getFirstPage(pageSize)
                    } else {
                        blogDao.getNextPage(
                            cursorPrefix = cursor.titlePrefix,
                            cursorTitle = cursor.title,
                            cursorId = cursor.id,
                            pageSize = pageSize
                        )
                    }
                }
            }

            val nextCursor = if (items.size < pageSize) {
                null // No more pages
            } else {
                items.lastOrNull()?.let { lastItem ->
                    BlogCursor(
                        titlePrefix = lastItem.title.take(10).uppercase(),
                        title = lastItem.title,
                        id = lastItem.id,
                        updatedAt = lastItem.updatedAt
                    )
                }
            }

            LoadResult.Page(
                data = items,
                prevKey = null, // Only forward paging
                nextKey = nextCursor
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading page: ${e.message}", e)
            LoadResult.Error(e)
        }
    }
}

/**
 * Cursor for stable pagination.
 * Includes all fields needed for both sort orders.
 */
data class BlogCursor(
    val titlePrefix: String,
    val title: String,
    val id: Long,
    val updatedAt: Long = 0L
)
