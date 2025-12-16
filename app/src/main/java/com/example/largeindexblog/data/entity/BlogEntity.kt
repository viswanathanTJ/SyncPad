package com.example.largeindexblog.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Blog entity representing a blog post in the database.
 * 
 * Schema:
 * CREATE TABLE blogs (
 *   id INTEGER PRIMARY KEY,
 *   title TEXT NOT NULL,
 *   content TEXT,
 *   title_prefix TEXT NOT NULL,
 *   created_at INTEGER NOT NULL,
 *   updated_at INTEGER NOT NULL,
 *   device_id TEXT NOT NULL
 * );
 */
@Entity(
    tableName = "blogs",
    indices = [
        Index(value = ["title_prefix"]),
        Index(value = ["created_at"]),
        Index(value = ["updated_at"])
    ]
)
data class BlogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content")
    val content: String? = null,

    @ColumnInfo(name = "title_prefix")
    val titlePrefix: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "device_id")
    val deviceId: String
) {
    companion object {
        /**
         * Generates the title prefix from a title.
         * The prefix is uppercase and limited to maxDepth characters.
         * 
         * @param title The blog title
         * @param maxDepth The maximum depth for prefix generation
         * @return The uppercase prefix of the title
         */
        fun generateTitlePrefix(title: String, maxDepth: Int): String {
            return title.take(maxDepth).uppercase()
        }
    }
}
