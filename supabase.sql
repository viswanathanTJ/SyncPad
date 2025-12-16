-- ============================================================================
-- SYNCPAD SUPABASE SCHEMA
-- ============================================================================
-- This SQL file defines the Supabase schema for the SyncPad blog application.
-- It includes the blogs table and commented seed data population for testing.
--
-- Usage:
-- 1. Run this in your Supabase SQL Editor
-- 2. Uncomment the seed data section to populate ~70k test records
-- ============================================================================

-- ============================================================================
-- DROP EXISTING TABLES (if recreating)
-- ============================================================================
-- DROP TABLE IF EXISTS blogs;

-- ============================================================================
-- BLOGS TABLE
-- ============================================================================
-- Main table for storing blog posts.
-- Note: title_prefix is computed client-side as UPPER(SUBSTR(title, 1, MAX_DEPTH))
-- The client (Android app) calculates this value locally for proper indexing.

CREATE TABLE IF NOT EXISTS blogs (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    content TEXT,
    title_prefix TEXT NOT NULL DEFAULT '',  -- Computed client-side: UPPER(SUBSTR(title, 1, 5))
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    device_id TEXT NOT NULL DEFAULT 'system'
);

-- ============================================================================
-- INDEXES
-- ============================================================================
-- Index on title_prefix for fast alphabet navigation
CREATE INDEX IF NOT EXISTS idx_blogs_title_prefix ON blogs(title_prefix);

-- Index on created_at for date filtering
CREATE INDEX IF NOT EXISTS idx_blogs_created_at ON blogs(created_at);

-- Index on updated_at for sync operations
CREATE INDEX IF NOT EXISTS idx_blogs_updated_at ON blogs(updated_at);

-- Composite index for efficient paging queries
CREATE INDEX IF NOT EXISTS idx_blogs_prefix_title ON blogs(title_prefix, title, id);

-- ============================================================================
-- HELPER FUNCTIONS
-- ============================================================================

-- Function to compute title_prefix from title (for server-side use if needed)
CREATE OR REPLACE FUNCTION compute_title_prefix(p_title TEXT, p_max_depth INT DEFAULT 5)
RETURNS TEXT AS $$
BEGIN
    RETURN UPPER(SUBSTRING(p_title FROM 1 FOR p_max_depth));
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Function to generate random title for seed data
CREATE OR REPLACE FUNCTION generate_random_title(p_prefix TEXT DEFAULT NULL)
RETURNS TEXT AS $$
DECLARE
    words TEXT[] := ARRAY[
        'Amazing', 'Beautiful', 'Creative', 'Delightful', 'Elegant',
        'Fantastic', 'Gorgeous', 'Happy', 'Incredible', 'Joyful',
        'Kind', 'Lovely', 'Magical', 'Natural', 'Outstanding',
        'Perfect', 'Quick', 'Radiant', 'Simple', 'Thoughtful',
        'Unique', 'Vibrant', 'Wonderful', 'Xtra', 'Young', 'Zealous',
        'Adventure', 'Building', 'Coding', 'Design', 'Engineering',
        'Framework', 'Guide', 'Handbook', 'Introduction', 'Journey',
        'Knowledge', 'Learning', 'Manual', 'Notes', 'Overview',
        'Project', 'Questions', 'Resources', 'Study', 'Tutorial',
        'Understanding', 'Vision', 'Workshop', 'eXample', 'Year', 'Zone'
    ];
    title TEXT;
BEGIN
    IF p_prefix IS NOT NULL THEN
        -- Start with the given prefix
        title := p_prefix || ' ' || words[1 + floor(random() * array_length(words, 1))::int];
    ELSE
        title := words[1 + floor(random() * array_length(words, 1))::int];
    END IF;
    
    -- Add 2-4 more words
    FOR i IN 1..2 + floor(random() * 3)::int LOOP
        title := title || ' ' || words[1 + floor(random() * array_length(words, 1))::int];
    END LOOP;
    
    RETURN title;
END;
$$ LANGUAGE plpgsql;

-- Function to generate random content
CREATE OR REPLACE FUNCTION generate_random_content()
RETURNS TEXT AS $$
DECLARE
    paragraphs TEXT[] := ARRAY[
        'This is a comprehensive guide covering all the essential aspects of the topic.',
        'In this document, we explore various concepts and provide practical examples.',
        'The following content provides detailed information and step-by-step instructions.',
        'Here you will find useful tips, tricks, and best practices for improvement.',
        'This note contains important information that should be reviewed carefully.',
        'The content below has been organized for easy reference and quick lookup.',
        'Learn about the key features and how to make the most of them.',
        'This section covers advanced topics for those who want to go deeper.',
        'Find answers to common questions and solutions to typical problems.',
        'A collection of resources and references for further reading and study.'
    ];
    content TEXT := '';
BEGIN
    -- Generate 3-8 paragraphs
    FOR i IN 1..(3 + floor(random() * 6)::int) LOOP
        content := content || paragraphs[1 + floor(random() * array_length(paragraphs, 1))::int] || E'\n\n';
    END LOOP;
    
    RETURN content;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- SEED DATA GENERATION FUNCTION
-- ============================================================================
-- This function generates seed data for testing.
-- Call: SELECT generate_seed_data(min_per_letter, max_per_letter);
-- For 70k records: SELECT generate_seed_data(2500, 3000); -- ~2750 avg * 26 = ~71k

CREATE OR REPLACE FUNCTION generate_seed_data(
    p_min_per_letter INT DEFAULT 2500,
    p_max_per_letter INT DEFAULT 3000
)
RETURNS TABLE(letter CHAR, inserted_count INT, total_count BIGINT) AS $$
DECLARE
    v_letter CHAR;
    v_count INT;
    v_inserted INT;
    v_now BIGINT;
    v_title TEXT;
    v_prefix TEXT;
BEGIN
    v_now := (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT;
    
    -- Generate records for each letter A-Z
    FOR i IN 0..25 LOOP
        v_letter := CHR(65 + i);  -- A=65, B=66, ..., Z=90
        v_count := p_min_per_letter + floor(random() * (p_max_per_letter - p_min_per_letter + 1))::int;
        v_inserted := 0;
        
        FOR j IN 1..v_count LOOP
            -- Generate title starting with this letter
            v_title := v_letter || generate_random_title(NULL);
            v_prefix := compute_title_prefix(v_title, 5);
            
            INSERT INTO blogs (title, content, title_prefix, created_at, updated_at, device_id)
            VALUES (
                v_title,
                generate_random_content(),
                v_prefix,
                v_now - (random() * 31536000000)::BIGINT,  -- Random time in last year
                v_now - (random() * 86400000)::BIGINT,     -- Random time in last day
                'seed-generator'
            );
            
            v_inserted := v_inserted + 1;
        END LOOP;
        
        letter := v_letter;
        inserted_count := v_inserted;
        SELECT COUNT(*) INTO total_count FROM blogs WHERE title_prefix LIKE v_letter || '%';
        RETURN NEXT;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- SEED DATA VERIFICATION FUNCTION
-- ============================================================================
-- Check the distribution of records across prefixes

CREATE OR REPLACE FUNCTION verify_seed_data()
RETURNS TABLE(
    prefix_char CHAR,
    count_depth1 BIGINT,
    sample_depth2 TEXT
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        LEFT(title_prefix, 1)::CHAR as prefix_char,
        COUNT(*) as count_depth1,
        string_agg(DISTINCT LEFT(title_prefix, 2), ', ' ORDER BY LEFT(title_prefix, 2)) as sample_depth2
    FROM blogs
    GROUP BY LEFT(title_prefix, 1)
    ORDER BY prefix_char;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- ROW LEVEL SECURITY (Optional)
-- ============================================================================
-- Uncomment if you want to enable RLS for multi-user scenarios

-- ALTER TABLE blogs ENABLE ROW LEVEL SECURITY;

-- -- Policy: Allow all authenticated users to read all blogs
-- CREATE POLICY "Allow all to read blogs" ON blogs
--     FOR SELECT
--     USING (true);

-- -- Policy: Allow authenticated users to insert their own blogs
-- CREATE POLICY "Allow authenticated to insert" ON blogs
--     FOR INSERT
--     WITH CHECK (auth.uid() IS NOT NULL);

-- -- Policy: Allow users to update their own blogs (by device_id)
-- CREATE POLICY "Allow update own blogs" ON blogs
--     FOR UPDATE
--     USING (device_id = current_setting('app.device_id', true));

-- ============================================================================
-- CLEANUP FUNCTION (for testing)
-- ============================================================================
-- Delete all seed data

CREATE OR REPLACE FUNCTION cleanup_seed_data()
RETURNS INT AS $$
DECLARE
    v_deleted INT;
BEGIN
    DELETE FROM blogs WHERE device_id = 'seed-generator';
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- USAGE EXAMPLES
-- ============================================================================
-- 
-- 1. Create the schema:
--    Just run this entire SQL file in Supabase SQL Editor
--
-- 2. Generate 70k+ test records (commented out to prevent accidental execution):
-- 
-- SELECT * FROM generate_seed_data(2500, 3000);
-- 
-- This creates ~2500-3000 records per letter (A-Z), totaling ~70,000 records.
-- Each record has:
--   - title: Random words starting with the letter
--   - content: 3-8 random paragraphs
--   - title_prefix: UPPER(SUBSTR(title, 1, 5))
--   - created_at: Random timestamp in last year
--   - updated_at: Random timestamp in last day
--   - device_id: 'seed-generator'
--
-- 3. Verify the data distribution:
-- 
-- SELECT * FROM verify_seed_data();
-- 
-- 4. Check total count:
-- 
-- SELECT COUNT(*) FROM blogs;
-- 
-- 5. Cleanup test data (if needed):
-- 
-- SELECT cleanup_seed_data();
-- 

-- ============================================================================
-- SEED DATA POPULATION (COMMENTED)
-- ============================================================================
-- Uncomment the following line to generate ~70k test records:
-- 
-- SELECT * FROM generate_seed_data(2500, 3000);
-- 
-- ============================================================================
