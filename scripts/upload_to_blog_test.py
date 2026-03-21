#!/usr/bin/env python3
"""
Upload all records from CSV backup to the `blog_test` table in Supabase.

Features:
- Reads credentials from local.properties in the project root
- Processes CSV in streaming fashion (memory-efficient for 3M+ rows)
- Upserts in configurable batches (default 500 rows per request)
- Resumable: tracks progress in a JSON sidecar file
- Skips rows already uploaded (based on last uploaded id)
- Prints live progress with ETA

Usage:
    python scripts/upload_to_blog_test.py [CSV_FILE]

    CSV_FILE defaults to the latest backup in scripts/output/.
    To reset progress and re-upload from scratch, delete the .progress file
    printed at startup.
"""

import csv
import json
import os
import sys
import time
import requests
from pathlib import Path

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SCRIPT_DIR = Path(__file__).parent.resolve()
PROJECT_ROOT = SCRIPT_DIR.parent
LOCAL_PROPS = PROJECT_ROOT / "local.properties"
OUTPUT_DIR = SCRIPT_DIR / "output"

TARGET_TABLE = "blogs_test"
BATCH_SIZE = 500          # rows per upsert request
REQUEST_TIMEOUT = 60      # seconds per HTTP request
RETRY_LIMIT = 3           # retries per failed batch
RETRY_DELAY = 5           # seconds between retries

# Columns that exist on the server table (controls what we send)
SERVER_COLUMNS = {
    "id", "title", "content", "title_prefix",
    "created_at", "updated_at", "device_id",
    "server_created_at", "server_updated_at",
    "is_deleted", "deleted_at",
}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def read_local_properties() -> dict:
    props = {}
    with open(LOCAL_PROPS, "r") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, val = line.split("=", 1)
                props[key.strip()] = val.strip()
    return props


def find_latest_csv() -> Path:
    csvs = sorted(OUTPUT_DIR.glob("blogs_backup_*.csv"))
    if not csvs:
        print(f"Error: no CSV backup found in {OUTPUT_DIR}")
        sys.exit(1)
    return csvs[-1]


def cast_row(row: dict) -> dict:
    """Convert CSV string values to correct Python types before uploading."""
    result = {}
    for k, v in row.items():
        if k not in SERVER_COLUMNS:
            continue
        # Empty string → None (NULL)
        if v == "":
            result[k] = None
            continue
        # Integer columns
        if k in ("id", "created_at", "updated_at"):
            result[k] = int(v)
        # Boolean column
        elif k == "is_deleted":
            result[k] = v.lower() in ("true", "1", "t", "yes")
        else:
            result[k] = v
    return result


def upsert_batch(batch: list, base_url: str, api_key: str) -> None:
    """Upsert a list of row dicts into TARGET_TABLE, retrying on transient errors."""
    url = f"{base_url}/rest/v1/{TARGET_TABLE}"
    headers = {
        "apikey": api_key,
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "Prefer": "resolution=merge-duplicates,return=minimal",
    }

    payload = json.dumps(batch, ensure_ascii=False)

    for attempt in range(1, RETRY_LIMIT + 1):
        try:
            resp = requests.post(url, data=payload, headers=headers, timeout=REQUEST_TIMEOUT)
            if resp.status_code in (200, 201):
                return
            # Surface the error detail from Supabase
            raise RuntimeError(
                f"HTTP {resp.status_code}: {resp.text[:400]}"
            )
        except (requests.exceptions.RequestException, RuntimeError) as exc:
            if attempt == RETRY_LIMIT:
                raise
            print(f"\n  [retry {attempt}/{RETRY_LIMIT}] {exc}  — waiting {RETRY_DELAY}s ...")
            time.sleep(RETRY_DELAY)


class ProgressTracker:
    """Persists upload progress so we can resume interrupted runs."""

    def __init__(self, csv_path: Path):
        self.path = csv_path.with_suffix(".progress.json")
        self.data = {"last_uploaded_id": 0, "rows_uploaded": 0}
        self._load()

    def _load(self):
        if self.path.exists():
            try:
                with open(self.path) as f:
                    self.data = json.load(f)
            except (json.JSONDecodeError, KeyError):
                pass  # corrupted → start fresh

    def save(self):
        with open(self.path, "w") as f:
            json.dump(self.data, f)

    @property
    def last_id(self) -> int:
        return self.data["last_uploaded_id"]

    @property
    def rows_uploaded(self) -> int:
        return self.data["rows_uploaded"]

    def update(self, last_id: int, count: int):
        self.data["last_uploaded_id"] = last_id
        self.data["rows_uploaded"] += count
        self.save()


def format_eta(elapsed: float, done: int, total: int) -> str:
    if done == 0:
        return "?"
    rate = done / elapsed          # rows/sec
    remaining = (total - done) / rate
    m, s = divmod(int(remaining), 60)
    h, m = divmod(m, 60)
    return f"{h:02d}:{m:02d}:{s:02d}"


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    # Determine CSV path
    if len(sys.argv) > 1:
        csv_path = Path(sys.argv[1]).resolve()
    else:
        csv_path = find_latest_csv()

    if not csv_path.exists():
        print(f"Error: CSV file not found: {csv_path}")
        sys.exit(1)

    # Credentials
    if not LOCAL_PROPS.exists():
        print(f"Error: {LOCAL_PROPS} not found")
        sys.exit(1)

    props = read_local_properties()
    base_url = props.get("SYNC_BASE_URL", "").rstrip("/")
    api_key = props.get("SYNC_API_KEY", "")

    if not base_url or not api_key:
        print("Error: SYNC_BASE_URL and SYNC_API_KEY must be set in local.properties")
        sys.exit(1)

    # Progress / resume
    progress = ProgressTracker(csv_path)

    print(f"Target table : {TARGET_TABLE}")
    print(f"CSV file     : {csv_path}")
    print(f"Progress file: {progress.path}")
    print(f"Batch size   : {BATCH_SIZE}")
    if progress.last_id > 0:
        print(f"Resuming from id > {progress.last_id} ({progress.rows_uploaded} rows already uploaded)")
    print()

    # Count total rows for ETA — must use csv.reader because content fields
    # contain embedded newlines inside quoted strings (raw line count is wrong).
    print("Counting rows ...", end=" ", flush=True)
    total_rows = 0
    with open(csv_path, newline="", encoding="utf-8") as f:
        reader = csv.reader(f)
        next(reader, None)  # skip header
        for _ in reader:
            total_rows += 1
    print(f"{total_rows:,} rows")

    already_done = progress.rows_uploaded
    start_time = time.time()
    batch: list = []
    rows_this_run = 0
    skipped = 0

    with open(csv_path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)

        for row in reader:
            row_id = int(row.get("id", 0))

            # Skip rows already uploaded in a previous run
            if row_id <= progress.last_id:
                skipped += 1
                continue

            batch.append(cast_row(row))

            if len(batch) >= BATCH_SIZE:
                upsert_batch(batch, base_url, api_key)
                last_id = batch[-1]["id"]
                progress.update(last_id, len(batch))
                rows_this_run += len(batch)
                batch.clear()

                # Progress line
                done_total = already_done + rows_this_run
                elapsed = time.time() - start_time
                pct = done_total / total_rows * 100 if total_rows else 0
                eta = format_eta(elapsed, rows_this_run, total_rows - already_done)
                print(
                    f"\r  Uploaded {done_total:>10,} / {total_rows:,}  "
                    f"({pct:.1f}%)  ETA {eta}",
                    end="",
                    flush=True,
                )

    # Flush remaining rows
    if batch:
        upsert_batch(batch, base_url, api_key)
        last_id = batch[-1]["id"]
        progress.update(last_id, len(batch))
        rows_this_run += len(batch)

    done_total = already_done + rows_this_run
    elapsed = time.time() - start_time
    print(f"\n\nDone! Uploaded {rows_this_run:,} rows in this run ({done_total:,} total).")
    print(f"Elapsed: {elapsed:.1f} seconds")


if __name__ == "__main__":
    main()
