# Sync Debug Test Cases (blogs_test)

## Scope
Debug-mode validation for:
1. Bulk insert upload
2. Partial fetch download
3. Continue/resume sync
4. Rebuild index phase

## Preconditions
- Run **debug build** only.
- Supabase has `blogs_test` table with same schema/policies as `blogs`.
- `local.properties` contains valid `SYNC_BASE_URL` and `SYNC_API_KEY`.
- Confirm log line appears at startup:
   - `DEBUG sync mode: using Supabase table 'blogs_test'`

## Test Case 1 — Bulk insert (upload)
### Setup
- Create 250+ local notes while offline (or before sync).

### Action
- Trigger incremental sync.

### Expected logs
- `[DEBUG_SYNC] STEP 1 started: upload local changes`
- Multiple upload batch log entries
- Server requests target `/rest/v1/blogs_test`

### Pass criteria
- Upload completes without error.
- Uploaded count in success status > 0.

## Test Case 2 — Partial fetch (download)
### Setup
- Insert 600+ rows into Supabase `blogs_test` newer than local `last_sync_time`.

### Action
- Trigger incremental sync.

### Expected logs
- `[DEBUG_SYNC] STEP 2 started: download server changes (stream + batch insert)`
- Progress updates: `Downloading...` percentage or item count
- Batch insertion logs at 500-item boundaries

### Pass criteria
- Local DB grows by expected amount.
- No stuck `Downloading 0%` during active processing.

## Test Case 3 — Continue/resume interrupted sync
### Setup
- Start incremental sync for large `blogs_test` dataset.
- Interrupt network/app during download stage.

### Action
- Reopen app and trigger auto/incremental sync.

### Expected logs
- Resume detection and cursor continuation
- `Resuming...` progress messages
- `[DEBUG_SYNC] STEP 6 started: clear sync progress flags` on completion

### Pass criteria
- Sync continues from previous cursor, not full restart.
- Final completion message appears.

## Test Case 4 — Rebuild phase verification (hard sync)
### Setup
- Ensure `blogs_test` has data.

### Action
- Trigger **Hard Sync** from settings.

### Expected logs
- `[DEBUG_SYNC] Hard sync started using table 'blogs_test'`
- `[DEBUG_SYNC] Hard sync rebuild phase started`
- `Rebuilding index...`
- `[DEBUG_SYNC] Hard sync complete: ...`

### Pass criteria
- Index rebuild completes.
- Alphabet/sidebar navigation works after hard sync.

## Final acceptance checklist
- All network calls use `blogs_test` in debug.
- Incremental progress messages are clear and non-stuck.
- Resume flow works.
- Rebuild flow works.
