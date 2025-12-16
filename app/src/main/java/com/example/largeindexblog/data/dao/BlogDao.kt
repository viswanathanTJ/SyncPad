package com.example.largeindexblog.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.largeindexblog.data.entity.BlogEntity
import com.example.largeindexblog.data.model.BlogListItem
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

    // ============================================
    // QUERY OPERATIONS
    // ============================================

    @Query("SELECT * FROM blogs WHERE id = :id")
    suspend fun getById(id: Long): BlogEntity?

    @Query("SELECT * FROM blogs WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<BlogEntity?>

    @Query("SELECT COUNT(*) FROM blogs")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM blogs")
    fun getCountFlow(): Flow<Int>

    // ============================================
    // PAGING QUERIES (Cursor-based, NO OFFSET)
    // Returns only id, title, created_at for list performance
    // ============================================

    /**
     * Get the first page of blogs ordered by title.
     * Uses cursor-based pagination for stability at 200k+ rows.
     */
    @Query("""
        SELECT id, title, created_at as createdAt
        FROM blogs
        ORDER BY title_prefix ASC, title ASC, id ASC
        LIMIT :pageSize
    """)
    suspend fun getFirstPage(pageSize: Int): List<BlogListItem>

    /**
     * Get the next page of blogs after a cursor position.
     * Cursor is based on (title_prefix, title, id) for stable sorting.
     */
    @Query("""
        SELECT id, title, created_at as createdAt
        FROM blogs
        WHERE (title_prefix > :cursorPrefix)
           OR (title_prefix = :cursorPrefix AND title > :cursorTitle)
           OR (title_prefix = :cursorPrefix AND title = :cursorTitle AND id > :cursorId)
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
        SELECT id, title, created_at as createdAt
        FROM blogs
        WHERE title_prefix LIKE :prefixPattern
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
        WHERE title_prefix LIKE :prefixPattern
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
        GROUP BY UPPER(SUBSTR(title, 1, :depth))
        ORDER BY prefix ASC
    """)
    suspend fun getPrefixCounts(depth: Int): List<PrefixCount>

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
}

/**
 * Data class for prefix count query results.
 */
data class PrefixCount(
    val prefix: String,
    val count: Int,
    val firstId: Long
)
