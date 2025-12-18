-- ============================================
-- RECYCLE BIN TABLE FOR SYNCPAD
-- ============================================
-- This table stores deleted blogs for recovery purposes.
-- When a blog is deleted from the app, it gets moved here
-- instead of being permanently deleted.

-- Create the recycle_bin table (mirrors blogs table structure)
CREATE TABLE IF NOT EXISTS recycle_bin (
  id BIGINT PRIMARY KEY,
  title TEXT NOT NULL,
  content TEXT,
  title_prefix VARCHAR(10),
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  device_id VARCHAR(100) NOT NULL,
  deleted_at BIGINT NOT NULL  -- Timestamp when the item was deleted
);

-- Create index on deleted_at for querying recent deletions
CREATE INDEX IF NOT EXISTS idx_recycle_bin_deleted_at ON recycle_bin(deleted_at DESC);

-- Create index on device_id for filtering by device
CREATE INDEX IF NOT EXISTS idx_recycle_bin_device_id ON recycle_bin(device_id);

-- Enable Row Level Security (RLS)
ALTER TABLE recycle_bin ENABLE ROW LEVEL SECURITY;

-- Policy: Allow all operations (same as blogs table)
-- Adjust this based on your security requirements
CREATE POLICY "Allow all operations on recycle_bin" ON recycle_bin
  FOR ALL 
  USING (true) 
  WITH CHECK (true);

-- Grant permissions to authenticated and anon roles
GRANT ALL ON recycle_bin TO authenticated;
GRANT ALL ON recycle_bin TO anon;

-- ============================================
-- OPTIONAL: Auto-cleanup old items
-- ============================================
-- Uncomment and adjust if you want to automatically delete
-- items older than 30 days from recycle bin

-- CREATE OR REPLACE FUNCTION cleanup_old_recycle_bin()
-- RETURNS void AS $$
-- BEGIN
--   DELETE FROM recycle_bin 
--   WHERE deleted_at < (EXTRACT(EPOCH FROM NOW()) * 1000) - (30 * 24 * 60 * 60 * 1000);
-- END;
-- $$ LANGUAGE plpgsql;

-- Schedule cleanup daily (requires pg_cron extension)
-- SELECT cron.schedule('cleanup-recycle-bin', '0 3 * * *', 'SELECT cleanup_old_recycle_bin()');
