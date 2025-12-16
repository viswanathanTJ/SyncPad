-- ============================================
-- Supabase SQL Schema for LargeIndexBlog
-- ============================================
-- This schema mirrors the Android Room database
-- for server-side sync functionality.
-- ============================================

-- Enable UUID extension for device_id generation
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================
-- BLOGS TABLE
-- ============================================
-- Main table for storing blog posts
-- Synced between server and client devices
CREATE TABLE IF NOT EXISTS blogs (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    content TEXT,
    title_prefix TEXT NOT NULL,
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    device_id TEXT NOT NULL,
    
    -- Server-side metadata
    server_created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    server_updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indexes for efficient querying
CREATE INDEX IF NOT EXISTS idx_blogs_title_prefix ON blogs(title_prefix);
CREATE INDEX IF NOT EXISTS idx_blogs_created_at ON blogs(created_at);
CREATE INDEX IF NOT EXISTS idx_blogs_updated_at ON blogs(updated_at);
CREATE INDEX IF NOT EXISTS idx_blogs_device_id ON blogs(device_id);

-- Index for sync queries (blogs modified after a timestamp)
CREATE INDEX IF NOT EXISTS idx_blogs_sync ON blogs(created_at, updated_at);

-- ============================================
-- SYNC TOKENS TABLE
-- ============================================
-- Optional: Track sync tokens per device
CREATE TABLE IF NOT EXISTS sync_tokens (
    device_id TEXT PRIMARY KEY,
    last_sync_time BIGINT NOT NULL DEFAULT 0,
    sync_token TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- ============================================
-- FUNCTIONS
-- ============================================

-- Function to automatically update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT;
    NEW.server_updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to auto-update timestamps on blogs
DROP TRIGGER IF EXISTS trigger_blogs_updated_at ON blogs;
CREATE TRIGGER trigger_blogs_updated_at
    BEFORE UPDATE ON blogs
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Function to generate title_prefix from title
CREATE OR REPLACE FUNCTION generate_title_prefix(title TEXT, max_depth INTEGER DEFAULT 5)
RETURNS TEXT AS $$
BEGIN
    RETURN UPPER(SUBSTRING(title FROM 1 FOR max_depth));
END;
$$ LANGUAGE plpgsql;

-- Trigger to auto-generate title_prefix on insert/update
CREATE OR REPLACE FUNCTION auto_generate_title_prefix()
RETURNS TRIGGER AS $$
BEGIN
    NEW.title_prefix = UPPER(SUBSTRING(NEW.title FROM 1 FOR 5));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_blogs_title_prefix ON blogs;
CREATE TRIGGER trigger_blogs_title_prefix
    BEFORE INSERT OR UPDATE OF title ON blogs
    FOR EACH ROW
    EXECUTE FUNCTION auto_generate_title_prefix();

-- ============================================
-- ROW LEVEL SECURITY (RLS)
-- ============================================
-- Enable RLS for production security
-- Uncomment and configure based on your auth setup

-- ALTER TABLE blogs ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE sync_tokens ENABLE ROW LEVEL SECURITY;

-- Example policy: Allow authenticated users to read all blogs
-- CREATE POLICY "Allow read access" ON blogs
--     FOR SELECT
--     USING (true);

-- Example policy: Allow users to insert/update their own blogs
-- CREATE POLICY "Allow insert" ON blogs
--     FOR INSERT
--     WITH CHECK (auth.uid() IS NOT NULL);

-- CREATE POLICY "Allow update own blogs" ON blogs
--     FOR UPDATE
--     USING (device_id = auth.uid()::text);

-- ============================================
-- SYNC API FUNCTIONS (RPC)
-- ============================================

-- Function to get blogs for incremental sync
CREATE OR REPLACE FUNCTION get_blogs_for_sync(
    p_last_sync_time BIGINT DEFAULT 0,
    p_limit INTEGER DEFAULT 1000
)
RETURNS SETOF blogs AS $$
BEGIN
    RETURN QUERY
    SELECT *
    FROM blogs
    WHERE created_at > p_last_sync_time
       OR updated_at > p_last_sync_time
    ORDER BY updated_at ASC
    LIMIT p_limit;
END;
$$ LANGUAGE plpgsql;

-- Function to upsert a blog (for sync from client)
CREATE OR REPLACE FUNCTION upsert_blog(
    p_id BIGINT,
    p_title TEXT,
    p_content TEXT,
    p_created_at BIGINT,
    p_updated_at BIGINT,
    p_device_id TEXT
)
RETURNS blogs AS $$
DECLARE
    result blogs;
BEGIN
    INSERT INTO blogs (id, title, content, title_prefix, created_at, updated_at, device_id)
    VALUES (
        p_id,
        p_title,
        p_content,
        UPPER(SUBSTRING(p_title FROM 1 FOR 5)),
        p_created_at,
        p_updated_at,
        p_device_id
    )
    ON CONFLICT (id) DO UPDATE SET
        title = EXCLUDED.title,
        content = EXCLUDED.content,
        title_prefix = EXCLUDED.title_prefix,
        updated_at = EXCLUDED.updated_at
    WHERE blogs.updated_at < EXCLUDED.updated_at
    RETURNING * INTO result;
    
    RETURN result;
END;
$$ LANGUAGE plpgsql;

-- Function to get blog count
CREATE OR REPLACE FUNCTION get_blog_count()
RETURNS BIGINT AS $$
BEGIN
    RETURN (SELECT COUNT(*) FROM blogs);
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- SAMPLE DATA (Optional - for testing)
-- ============================================
-- Uncomment to insert sample data

-- INSERT INTO blogs (title, content, device_id) VALUES
--     ('Apple Picking Season', 'The best time to pick apples is in early fall...', 'sample-device'),
--     ('Banana Bread Recipe', 'A delicious banana bread recipe...', 'sample-device'),
--     ('Coding Best Practices', 'Here are some coding tips...', 'sample-device'),
--     ('Database Design Patterns', 'Understanding database normalization...', 'sample-device'),
--     ('Effective Communication', 'Tips for better team communication...', 'sample-device');
