package com.viswa2k.syncpad.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Prefix index entity for fast navigation.
 * This is LOCAL ONLY derived data - not synced to server.
 * 
 * Schema:
 * CREATE TABLE prefix_index (
 *   prefix TEXT NOT NULL,
 *   depth INTEGER NOT NULL,
 *   count INTEGER NOT NULL,
 *   first_blog_id INTEGER NOT NULL,
 *   PRIMARY KEY (prefix, depth)
 * );
 */
@Entity(
    tableName = "prefix_index",
    primaryKeys = ["prefix", "depth"]
)
data class PrefixIndexEntity(
    @ColumnInfo(name = "prefix")
    val prefix: String,

    @ColumnInfo(name = "depth")
    val depth: Int,

    @ColumnInfo(name = "count")
    val count: Int,

    @ColumnInfo(name = "first_blog_id")
    val firstBlogId: Long
)
