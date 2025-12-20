package com.viswa2k.syncpad.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.viswa2k.syncpad.data.entity.BlogEntity
import com.viswa2k.syncpad.data.model.BlogListItem
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for blog operations.
 * All queries handle exceptions at the repository layer.
 */
@Dao
interface BlogDao {

    // ============================================
    // INSERT OPERATIONS
    // ============================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(blog: BlogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(blogs: List<BlogEntity>): List<Long>

    // ============================================
    // UPDATE OPERATIONS
    // ============================================

    @Update
    suspend fun update(blog: BlogEntity): Int

    // ============================================
    // DELETE OPERATIONS
    // ============================================

    @Delete
    suspend fun delete(blog: BlogEntity): Int

    @Query("DELETE FROM blogs WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM blogs")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM blogs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    /**
     * Soft delete a blog by setting is_deleted = true.
     * Updates updated_at and deleted_at timestamps.
     */
    @Query("UPDATE blogs SET is_deleted = 1, deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDeleteById(id: Long, deletedAt: Long, updatedAt: Long): Int

    /**
     * Get blogs that were soft-deleted after a timestamp.
     * Used during sync to detect server-side deletions.
     */
    @Query("SELECT id FROM blogs WHERE is_deleted = 1 AND updated_at > :afterTimestamp")
    suspend fun getDeletedAfter(afterTimestamp: Long): List<Long>

    // ============================================
    // QUERY OPERATIONS
    // ============================================

    @Query("SELECT * FROM blogs WHERE id = :id")
    suspend fun getById(id: Long): BlogEntity?

    @Query("SELECT * FROM blogs WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<BlogEntity?>

    @Query("SELECT COUNT(*) FROM blogs WHERE is_deleted = 0")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM blogs WHERE is_deleted = 0")
    fun getCountFlow(): Flow<Int>

    @Query("SELECT id FROM blogs WHERE is_deleted = 0")
    suspend fun getAllIds(): List<Long>

    @Query("SELECT COUNT(*) FROM blogs WHERE UPPER(SUBSTR(title, 1, :prefixLength)) = UPPER(:prefix) AND is_deleted = 0")
    suspend fun getCountByPrefix(prefix: String, prefixLength: Int = prefix.length): Int

    // ============================================
    // PAGING QUERIES (Cursor-based, NO OFFSET)
    // Returns only id, title, created_at for list performance
    // ============================================

    /**
     * Get the first page of blogs ordered by title.
     * Uses cursor-based pagination for stability at 200k+ rows.
     */
    @Query("""
        SELECT id, title, title_prefix as titlePrefix, created_at as createdAt
        FROM blogs
        WHERE is_deleted = 0
        ORDER BY title_prefix ASC, title ASC, id ASC
        LIMIT :pageSize
    """)
    suspend fun getFirstPage(pageSize: Int): List<BlogListItem>

    /**
     * Get the next page of blogs after a cursor position.
     * Cursor is based on (title_prefix, title, id) for stable sorting.
     */
    @Query("""
        SELECT id, title, title_prefix as titlePrefix, created_at as createdAt
        FROM blogs
        WHERE is_deleted = 0 AND (
           (title_prefix > :cursorPrefix)
           OR (title_prefix = :cursorPrefix AND title > :cursorTitle)
           OR (title_prefix = :cursorPrefix AND title = :cursorTitle AND id > :cursorId)
        )
        ORDER BY title_prefix ASC, title ASC, id ASC
        LIMIT :pageSize
    """)
    suspend fun getNextPage(
        cursorPrefix: String,
        cursorTitle: String,
        cursorId: Long,
        pageSize: Int
    ): List<BlogListItem>

    /**
     * Get blogs starting with a specific prefix for alphabet navigation.
     */
    @Query("""
        SELECT id, title, title_prefix as titlePrefix, created_at as createdAt
        FROM blogs
        WHERE title_prefix LIKE :prefixPattern AND is_deleted = 0
        ORDER BY title_prefix ASC, title ASC, id ASC
        LIMIT :pageSize
    """)
    suspend fun getByPrefixPattern(prefixPattern: String, pageSize: Int): List<BlogListItem>

    /**
     * Get the first blog ID for a given prefix.
     * Used for alphabet sidebar navigation.
     */
    @Query("""
        SELECT id FROM blogs
        WHERE title_prefix LIKE :prefixPattern AND is_deleted = 0
        ORDER BY title_prefix ASC, title ASC, id ASC
        LIMIT 1
    """)
    suspend fun getFirstIdByPrefix(prefixPattern: String): Long?

    // ============================================
    // PREFIX INDEX BUILDING QUERIES
    // ============================================

    /**
     * Get all distinct prefixes at a specific depth.
     * Used for building the prefix index.
     */
    @Query("""
        SELECT UPPER(SUBSTR(title, 1, :depth)) as prefix,
               COUNT(*) as count,
               MIN(id) as firstId
        FROM blogs
        WHERE is_deleted = 0
        GROUP BY UPPER(SUBSTR(title, 1, :depth))
        ORDER BY prefix ASC
    """)
    suspend fun getPrefixCounts(depth: Int): List<PrefixCount>

    /**
     * Get child prefix counts for a specific parent prefix.
     * Used for popup drill-down navigation.
     * Example: parentPrefix="C" returns counts for CA, CB, CC, etc.
     */
    @Query("""
        SELECT UPPER(SUBSTR(title, 1, :childDepth)) as prefix,
               COUNT(*) as count,
               MIN(id) as firstId
        FROM blogs
        WHERE UPPER(SUBSTR(title, 1, :parentLength)) = :parentPrefix AND is_deleted = 0
        GROUP BY UPPER(SUBSTR(title, 1, :childDepth))
        ORDER BY prefix ASC
    """)
    suspend fun getChildPrefixCounts(
        parentPrefix: String,
        parentLength: Int,
        childDepth: Int
    ): List<PrefixCount>

    /**
     * Get blogs for sync - those created or updated after a timestamp.
     */
    @Query("""
        SELECT * FROM blogs
        WHERE created_at > :afterTimestamp OR updated_at > :afterTimestamp
        ORDER BY updated_at ASC
    """)
    suspend fun getBlogsForSync(afterTimestamp: Long): List<BlogEntity>

    /**
     * Get the title_prefix for a blog by ID.
     */
    @Query("SELECT title_prefix FROM blogs WHERE id = :id")
    suspend fun getTitlePrefixById(id: Long): String?

    // ============================================
    // SEARCH QUERIES
    // ============================================

    /**
     * Search blogs by title only (fast, title-first search).
     */
    @Query("""
        SELECT id, title, title_prefix as titlePrefix, created_at as createdAt
        FROM blogs
        WHERE title LIKE :query AND is_deleted = 0
        ORDER BY title ASC
        LIMIT :limit
    """)
    suspend fun searchByTitle(query: String, limit: Int = 50): List<BlogListItem>

    /**
     * Search blogs by title OR content (advanced search).
     */
    @Query("""
        SELECT id, title, title_prefix as titlePrefix, created_at as createdAt
        FROM blogs
        WHERE (title LIKE :query OR content LIKE :query) AND is_deleted = 0
        ORDER BY title ASC
        LIMIT :limit
    """)
    suspend fun searchByTitleOrContent(query: String, limit: Int = 50): List<BlogListItem>

    /**
     * Advanced search with date filters.
     * All date parameters are optional (pass 0 to ignore).
     */
    @Query("""
        SELECT id, title, title_prefix as titlePrefix, created_at as createdAt
        FROM blogs
        WHERE (title LIKE :query OR (:includeContent = 1 AND content LIKE :query))
          AND is_deleted = 0
          AND (:createdAfter = 0 OR created_at > :createdAfter)
          AND (:createdBefore = 0 OR created_at < :createdBefore)
          AND (:updatedAfter = 0 OR updated_at > :updatedAfter)
          AND (:updatedBefore = 0 OR updated_at < :updatedBefore)
        ORDER BY title ASC
        LIMIT :limit
    """)
    suspend fun advancedSearch(
        query: String,
        includeContent: Int = 0,
        createdAfter: Long = 0,
        createdBefore: Long = 0,
        updatedAfter: Long = 0,
        updatedBefore: Long = 0,
        limit: Int = 50
    ): List<BlogListItem>
}

/**
 * Data class for prefix count query results.
 */
data class PrefixCount(
    val prefix: String,
    val count: Int,
    val firstId: Long
)
