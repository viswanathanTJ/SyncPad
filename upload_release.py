#!/usr/bin/env python3
"""
Upload a SyncPad APK to Supabase Storage and register it in app_versions.

Usage:
    python upload_release.py app/build/outputs/apk/release/app-release.apk 1.0.1 --notes "Bug fixes"
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys
from pathlib import Path

try:
    from supabase import create_client
except ImportError:
    print("❌ Missing dependency: supabase")
    print("   Run with: uv run --with supabase upload_release.py ...")
    sys.exit(1)


def get_local_properties(project_root: Path) -> dict[str, str]:
    props_path = project_root / "local.properties"
    props: dict[str, str] = {}
    if not props_path.exists():
        return props

    for raw_line in props_path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def get_supabase_credentials(project_root: Path) -> tuple[str | None, str | None]:
    props = get_local_properties(project_root)
    url = props.get("SYNC_BASE_URL") or os.environ.get("SYNC_BASE_URL") or os.environ.get("SUPABASE_URL")
    key = (
        props.get("SUPABASE_SERVICE_KEY")
        or os.environ.get("SUPABASE_SERVICE_KEY")
        or props.get("SYNC_API_KEY")
        or os.environ.get("SYNC_API_KEY")
        or os.environ.get("SUPABASE_KEY")
    )
    return url, key


def calculate_md5(file_path: Path) -> str:
    digest = hashlib.md5()
    with file_path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8192), b""):
            digest.update(chunk)
    return digest.hexdigest()


def version_name_to_code(version_name: str) -> int:
    parts = [int(part) for part in version_name.split(".")]
    while len(parts) < 3:
        parts.append(0)
    major, minor, patch = parts[:3]
    return major * 10000 + minor * 100 + patch


def main() -> None:
    parser = argparse.ArgumentParser(description="Upload SyncPad APK to Supabase")
    parser.add_argument("apk_path", help="Path to the APK file")
    parser.add_argument("version_name", help="Version name, for example 1.0.1")
    parser.add_argument("--force", action="store_true", help="Mark this release as a forced update")
    parser.add_argument("--notes", default="", help="Release notes")
    parser.add_argument("--min-version", type=int, default=1, help="Minimum supported version code")
    parser.add_argument("--version-code", type=int, help="Override the derived version code")
    parser.add_argument("--bucket", default="app-releases", help="Supabase storage bucket")
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parent
    apk_path = (project_root / args.apk_path).resolve() if not Path(args.apk_path).is_absolute() else Path(args.apk_path)
    if not apk_path.exists():
        print(f"❌ APK not found: {apk_path}")
        sys.exit(1)

    url, key = get_supabase_credentials(project_root)
    if not url or not key:
        print("❌ Missing SYNC_BASE_URL and/or SUPABASE_SERVICE_KEY")
        print("   Add them to local.properties or export them as environment variables.")
        sys.exit(1)

    version_code = args.version_code or version_name_to_code(args.version_name)
    file_size = apk_path.stat().st_size
    checksum = calculate_md5(apk_path)

    print("🔗 Connecting to Supabase...")
    supabase = create_client(url, key)

    storage_name = f"syncpad-v{args.version_name}.apk"
    print(f"📦 Uploading {storage_name} ({file_size / (1024 * 1024):.2f} MB)")

    try:
        with apk_path.open("rb") as handle:
            supabase.storage.from_(args.bucket).upload(
                path=storage_name,
                file=handle,
                file_options={"content-type": "application/vnd.android.package-archive"},
            )
    except Exception as exc:
        if "Duplicate" in str(exc) or "already exists" in str(exc).lower():
            print("   ⚠️ Release already exists, updating existing APK...")
            with apk_path.open("rb") as handle:
                supabase.storage.from_(args.bucket).update(
                    path=storage_name,
                    file=handle,
                    file_options={"content-type": "application/vnd.android.package-archive"},
                )
        else:
            print(f"❌ Upload failed: {exc}")
            sys.exit(1)

    public_url = supabase.storage.from_(args.bucket).get_public_url(storage_name)
    version_record = {
        "version_code": version_code,
        "version_name": args.version_name,
        "apk_url": public_url,
        "release_notes": args.notes or f"Version {args.version_name}",
        "is_force_update": args.force,
        "min_supported_version": args.min_version,
        "file_size_bytes": file_size,
        "checksum_md5": checksum,
        "is_active": True,
    }

    try:
        supabase.table("app_versions").upsert(version_record, on_conflict="version_code").execute()
    except Exception as exc:
        print(f"❌ Failed to create app_versions row: {exc}")
        print("   Run supabase_app_update_setup.sql first.")
        sys.exit(1)

    print(f"✅ Published SyncPad v{args.version_name} ({version_code})")
    print(f"🔗 APK URL: {public_url}")
    if args.force:
        print("⚠️ This release is marked as a force update.")


if __name__ == "__main__":
    main()
