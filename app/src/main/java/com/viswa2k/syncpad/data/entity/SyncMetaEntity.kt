package com.viswa2k.syncpad.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Sync metadata entity for storing sync-related key-value pairs.
 * 
 * Schema:
 * CREATE TABLE sync_meta (
 *   key TEXT PRIMARY KEY,
 *   value TEXT
 * );
 * 
 * Common keys:
 * - "last_sync_time" - Unix timestamp of last successful sync
 * - "sync_token" - Server-provided sync token for incremental sync
 */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String?
) {
    companion object {
        const val KEY_LAST_SYNC_TIME = "last_sync_time"
        const val KEY_SYNC_TOKEN = "sync_token"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SYNC_IN_PROGRESS = "sync_in_progress"
        const val KEY_SYNC_LAST_ID = "sync_last_id"
        const val KEY_SYNC_TOTAL_EXPECTED = "sync_total_expected"
    }
}
