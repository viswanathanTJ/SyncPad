package com.viswa2k.syncpad.data.model

/**
 * Lightweight model for blog list display.
 * Contains only the fields needed for the list view to optimize memory and performance.
 * NEVER includes content field - that is loaded only in detail view.
 */
data class BlogListItem(
    val id: Long,
    val title: String,
    val titlePrefix: String,
    val createdAt: Long,
    val updatedAt: Long = 0L
)
