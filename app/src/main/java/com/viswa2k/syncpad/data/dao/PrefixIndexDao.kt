package com.viswa2k.syncpad.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.viswa2k.syncpad.data.entity.PrefixIndexEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for prefix index operations.
 * The prefix index is LOCAL ONLY and not synced to server.
 */
@Dao
interface PrefixIndexDao {

    // ============================================
    // INSERT OPERATIONS
    // ============================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(prefixIndex: PrefixIndexEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(prefixIndices: List<PrefixIndexEntity>)

    // ============================================
    // DELETE OPERATIONS
    // ============================================

    @Query("DELETE FROM prefix_index")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM prefix_index WHERE depth = :depth")
    suspend fun deleteByDepth(depth: Int): Int

    @Query("DELETE FROM prefix_index WHERE prefix = :prefix AND depth = :depth")
    suspend fun deleteByPrefix(prefix: String, depth: Int): Int

    // ============================================
    // QUERY OPERATIONS
    // ============================================

    /**
     * Get all prefix indices ordered by prefix.
     */
    @Query("SELECT * FROM prefix_index ORDER BY prefix ASC, depth ASC")
    suspend fun getAll(): List<PrefixIndexEntity>

    /**
     * Get all prefix indices as a Flow for reactive updates.
     */
    @Query("SELECT * FROM prefix_index ORDER BY prefix ASC, depth ASC")
    fun getAllFlow(): Flow<List<PrefixIndexEntity>>

    /**
     * Get prefix indices at a specific depth.
     */
    @Query("SELECT * FROM prefix_index WHERE depth = :depth ORDER BY prefix ASC")
    suspend fun getByDepth(depth: Int): List<PrefixIndexEntity>

    /**
     * Get prefix index for a specific prefix at depth 1 (single character).
     * Used for alphabet sidebar.
     */
    @Query("SELECT * FROM prefix_index WHERE depth = 1 ORDER BY prefix ASC")
    suspend fun getAlphabetIndex(): List<PrefixIndexEntity>

    /**
     * Get prefix index as Flow for alphabet sidebar.
     */
    @Query("SELECT * FROM prefix_index WHERE depth = 1 ORDER BY prefix ASC")
    fun getAlphabetIndexFlow(): Flow<List<PrefixIndexEntity>>

    /**
     * Get prefix indices that start with a given prefix.
     */
    @Query("""
        SELECT * FROM prefix_index 
        WHERE prefix LIKE :prefixPattern AND depth > :currentDepth
        ORDER BY prefix ASC, depth ASC
    """)
    suspend fun getSubPrefixes(prefixPattern: String, currentDepth: Int): List<PrefixIndexEntity>

    /**
     * Get the first blog ID for a specific prefix.
     */
    @Query("SELECT first_blog_id FROM prefix_index WHERE prefix = :prefix AND depth = :depth")
    suspend fun getFirstBlogId(prefix: String, depth: Int): Long?

    /**
     * Check if we have any index entries.
     */
    @Query("SELECT COUNT(*) FROM prefix_index")
    suspend fun getCount(): Int

    /**
     * Transaction helper for full rebuild.
     */
    @Transaction
    suspend fun replaceAll(prefixIndices: List<PrefixIndexEntity>) {
        deleteAll()
        insertAll(prefixIndices)
    }
}
