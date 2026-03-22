#!/usr/bin/env python3
import csv
from pathlib import Path

oldf = Path("output/blogs_backup_20260321_231927.csv")
newf = Path("output/blogs_backup_20260322_002303.csv")
report = Path("output/snapshot_diff_report.txt")


def load(path: Path):
    ids = set()
    flags = {}
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            i = int(row["id"])
            ids.add(i)
            flags[i] = (row.get("is_deleted") or "").strip().lower() in {"true", "1", "t", "yes"}
    return ids, flags


old_ids, old_flags = load(oldf)
new_ids, new_flags = load(newf)

missing = sorted(old_ids - new_ids)
added = sorted(new_ids - old_ids)
common = old_ids & new_ids
active_to_deleted = [i for i in common if (not old_flags.get(i, False)) and new_flags.get(i, False)]
deleted_to_active = [i for i in common if old_flags.get(i, False) and (not new_flags.get(i, False))]

lines = [
    f"old_count={len(old_ids)}",
    f"new_count={len(new_ids)}",
    f"missing_from_new={len(missing)}",
    f"added_in_new={len(added)}",
    f"active_to_deleted={len(active_to_deleted)}",
    f"deleted_to_active={len(deleted_to_active)}",
]
if missing:
    lines.append(f"missing_sample={missing[:20]}")
if added:
    lines.append(f"added_sample={added[:20]}")
if active_to_deleted:
    lines.append(f"active_to_deleted_sample={active_to_deleted[:20]}")
if deleted_to_active:
    lines.append(f"deleted_to_active_sample={deleted_to_active[:20]}")

text = "\n".join(lines) + "\n"
report.write_text(text, encoding="utf-8")
print(text)
