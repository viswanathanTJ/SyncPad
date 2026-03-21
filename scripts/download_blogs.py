#!/usr/bin/env python3
"""
Download entire blogs table from Supabase and save in all formats:
  - JSON (.json)
  - JSONL (.jsonl) - one JSON object per line
  - CSV (.csv)

Reads credentials from local.properties in the project root.
Uses cursor-based pagination to handle large datasets.
Output is saved to scripts/output/ directory.
"""

import csv
import json
import os
import sys
import time
import requests

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
LOCAL_PROPS = os.path.join(PROJECT_ROOT, "local.properties")
OUTPUT_DIR = os.path.join(SCRIPT_DIR, "output")
PAGE_SIZE = 1000


def read_local_properties():
    props = {}
    with open(LOCAL_PROPS, "r") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                props[key.strip()] = value.strip()
    return props


def fetch_blogs(base_url, api_key):
    """Fetch all blogs using cursor-based pagination (by id)."""
    url = f"{base_url}/rest/v1/blogs"
    headers = {
        "apikey": api_key,
        "Authorization": f"Bearer {api_key}",
        "Accept": "application/json",
    }

    all_rows = []
    last_id = 0
    page = 0

    while True:
        resp = requests.get(
            url,
            headers=headers,
            params={
                "select": "*",
                "order": "id.asc",
                "id": f"gt.{last_id}",
                "limit": PAGE_SIZE,
            },
        )
        resp.raise_for_status()

        rows = resp.json()
        if not rows:
            break

        all_rows.extend(rows)
        page += 1
        print(f"  Page {page}: fetched {len(rows)} rows (total so far: {len(all_rows)})")

        if len(rows) < PAGE_SIZE:
            break

        last_id = rows[-1]["id"]
        time.sleep(0.1)

    return all_rows


def save_json(rows, filepath):
    with open(filepath, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)
    size_mb = os.path.getsize(filepath) / (1024 * 1024)
    print(f"  JSON:  {filepath} ({size_mb:.1f} MB)")


def save_jsonl(rows, filepath):
    with open(filepath, "w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    size_mb = os.path.getsize(filepath) / (1024 * 1024)
    print(f"  JSONL: {filepath} ({size_mb:.1f} MB)")


def save_csv(rows, filepath):
    if not rows:
        return
    columns = rows[0].keys()
    with open(filepath, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=columns, quoting=csv.QUOTE_ALL)
        writer.writeheader()
        writer.writerows(rows)
    size_mb = os.path.getsize(filepath) / (1024 * 1024)
    print(f"  CSV:   {filepath} ({size_mb:.1f} MB)")


def main():
    if not os.path.exists(LOCAL_PROPS):
        print(f"Error: {LOCAL_PROPS} not found")
        sys.exit(1)

    props = read_local_properties()
    base_url = props.get("SYNC_BASE_URL")
    api_key = props.get("SYNC_API_KEY")

    if not base_url or not api_key:
        print("Error: SYNC_BASE_URL and SYNC_API_KEY must be set in local.properties")
        sys.exit(1)

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    timestamp = time.strftime("%Y%m%d_%H%M%S")
    base_name = f"blogs_backup_{timestamp}"

    print(f"Supabase URL: {base_url}")
    print(f"Output dir:   {OUTPUT_DIR}")
    print(f"Page size:    {PAGE_SIZE}\n")

    start = time.time()
    rows = fetch_blogs(base_url, api_key)

    if not rows:
        print("No rows fetched.")
        sys.exit(1)

    print(f"\nFetched {len(rows)} rows. Saving in all formats...")
    save_json(rows, os.path.join(OUTPUT_DIR, f"{base_name}.json"))
    save_jsonl(rows, os.path.join(OUTPUT_DIR, f"{base_name}.jsonl"))
    save_csv(rows, os.path.join(OUTPUT_DIR, f"{base_name}.csv"))

    elapsed = time.time() - start
    print(f"\nCompleted in {elapsed:.1f} seconds")


if __name__ == "__main__":
    main()
