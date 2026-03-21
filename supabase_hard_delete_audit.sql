-- ============================================
-- HARD DELETE AUDIT FOR SYNCPAD BLOGS
-- ============================================
-- Purpose:
--   Capture every physical DELETE from `blogs` into an audit table,
--   so hard-deleted rows can be traced and recovered if needed.

-- 1) Archive table
CREATE TABLE IF NOT EXISTS hard_deleted_blogs (
  archive_id BIGSERIAL PRIMARY KEY,
  blog_id BIGINT NOT NULL,

  -- Useful searchable fields copied from the deleted row
  title TEXT,
  title_prefix TEXT,
  content TEXT,
  created_at BIGINT,
  updated_at BIGINT,
  device_id TEXT,
  is_deleted BOOLEAN,
  deleted_at BIGINT,

  -- Full raw row snapshot (future-proof if schema changes)
  deleted_row JSONB NOT NULL,

  -- Audit metadata
  hard_deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deleted_by TEXT,
  delete_txid BIGINT NOT NULL DEFAULT txid_current()
);

CREATE INDEX IF NOT EXISTS idx_hard_deleted_blogs_blog_id
  ON hard_deleted_blogs(blog_id);

CREATE INDEX IF NOT EXISTS idx_hard_deleted_blogs_hard_deleted_at
  ON hard_deleted_blogs(hard_deleted_at DESC);

CREATE INDEX IF NOT EXISTS idx_hard_deleted_blogs_device_id
  ON hard_deleted_blogs(device_id);

-- 2) Trigger function to archive row before hard delete
CREATE OR REPLACE FUNCTION archive_hard_deleted_blog()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_deleted_by TEXT;
BEGIN
  -- Works for API requests (JWT claim) and fallback SQL sessions.
  v_deleted_by := COALESCE(
    NULLIF(current_setting('request.jwt.claim.sub', true), ''),
    NULLIF(current_setting('request.jwt.claim.email', true), ''),
    current_user
  );

  INSERT INTO hard_deleted_blogs (
    blog_id,
    title,
    title_prefix,
    content,
    created_at,
    updated_at,
    device_id,
    is_deleted,
    deleted_at,
    deleted_row,
    deleted_by
  )
  VALUES (
    OLD.id,
    OLD.title,
    OLD.title_prefix,
    OLD.content,
    OLD.created_at,
    OLD.updated_at,
    OLD.device_id,
    OLD.is_deleted,
    OLD.deleted_at,
    to_jsonb(OLD),
    v_deleted_by
  );

  RETURN OLD;
END;
$$;

-- 3) Attach trigger to blogs table
DROP TRIGGER IF EXISTS trigger_archive_hard_deleted_blog ON blogs;
CREATE TRIGGER trigger_archive_hard_deleted_blog
BEFORE DELETE ON blogs
FOR EACH ROW
EXECUTE FUNCTION archive_hard_deleted_blog();

-- 4) Security (optional; adjust to your auth model)
ALTER TABLE hard_deleted_blogs ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow read hard delete archive" ON hard_deleted_blogs;
CREATE POLICY "Allow read hard delete archive" ON hard_deleted_blogs
FOR SELECT
USING (true);

DROP POLICY IF EXISTS "Allow insert hard delete archive" ON hard_deleted_blogs;
CREATE POLICY "Allow insert hard delete archive" ON hard_deleted_blogs
FOR INSERT
WITH CHECK (true);

GRANT SELECT, INSERT ON hard_deleted_blogs TO anon;
GRANT SELECT, INSERT ON hard_deleted_blogs TO authenticated;

-- ============================================
-- QUICK CHECKS
-- ============================================
-- SELECT COUNT(*) FROM hard_deleted_blogs;
-- SELECT blog_id, hard_deleted_at, deleted_by FROM hard_deleted_blogs ORDER BY hard_deleted_at DESC LIMIT 20;
