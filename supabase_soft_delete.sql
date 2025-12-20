-- ============================================
-- SOFT DELETE COLUMN FOR SYNCPAD BLOGS
-- ============================================
-- Adds is_deleted column for efficient sync deletion detection.
-- Instead of fetching all IDs, sync only fetches is_deleted=true records.

-- Add is_deleted column (default false for existing records)
ALTER TABLE blogs ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN DEFAULT FALSE;

-- Add deleted_at timestamp for tracking when deletion occurred
ALTER TABLE blogs ADD COLUMN IF NOT EXISTS deleted_at BIGINT DEFAULT NULL;

-- Create index for efficient querying of deleted records during sync
CREATE INDEX IF NOT EXISTS idx_blogs_is_deleted ON blogs(is_deleted) WHERE is_deleted = TRUE;

-- Create index for querying by updated_at + is_deleted (sync query)
CREATE INDEX IF NOT EXISTS idx_blogs_sync_deleted ON blogs(updated_at, is_deleted);

-- ============================================
-- USAGE NOTES
-- ============================================
-- Instead of DELETE FROM blogs WHERE id = X, use:
--   UPDATE blogs SET is_deleted = TRUE, deleted_at = EXTRACT(EPOCH FROM NOW()) * 1000, updated_at = EXTRACT(EPOCH FROM NOW()) * 1000 WHERE id = X;
--
-- Sync query for detecting deletions since last sync:
--   SELECT id FROM blogs WHERE is_deleted = TRUE AND updated_at > :lastSyncTime
--
-- Normal sync query should exclude deleted items:
--   SELECT * FROM blogs WHERE is_deleted = FALSE AND (created_at > :lastSyncTime OR updated_at > :lastSyncTime)
