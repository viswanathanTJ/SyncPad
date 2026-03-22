#!/usr/bin/env python3
import csv
from pathlib import Path

files = [
    Path("output/blogs_backup_20260321_231927.csv"),
    Path("output/blogs_backup_20260322_002303.csv"),
]

for p in files:
    total = 0
    deleted = 0
    min_id = None
    max_id = None

    with p.open(newline="", encoding="utf-8") as f:
        r = csv.DictReader(f)
        for row in r:
            i = int(row["id"])
            total += 1
            min_id = i if min_id is None or i < min_id else min_id
            max_id = i if max_id is None or i > max_id else max_id
            is_deleted = (row.get("is_deleted") or "").strip().lower() in {"true", "1", "t", "yes"}
            if is_deleted:
                deleted += 1

    active = total - deleted
    print(f"{p}: total={total} active={active} deleted={deleted} min_id={min_id} max_id={max_id}")
