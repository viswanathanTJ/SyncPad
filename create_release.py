#!/usr/bin/env python3
"""
Create a signed SyncPad release APK and optionally upload it to Supabase.

Examples:
    python create_release.py 1.0.1 --notes "Bug fixes"
    python create_release.py --bump patch --notes "Small improvements"
    python create_release.py --bump minor --force --notes "Important release"
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent
APP_BUILD_GRADLE = PROJECT_ROOT / "app" / "build.gradle.kts"
LOCAL_PROPERTIES = PROJECT_ROOT / "local.properties"
DEFAULT_KEYSTORE_PATH = Path.home() / "Documents" / "viswa2k.jks"
RELEASE_APK_PATH = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
UNSIGNED_APK_PATH = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release-unsigned.apk"


def get_local_properties() -> dict[str, str]:
    props: dict[str, str] = {}
    if not LOCAL_PROPERTIES.exists():
        return props

    for raw_line in LOCAL_PROPERTIES.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def get_current_version() -> tuple[int, str]:
    content = APP_BUILD_GRADLE.read_text()
    version_code_match = re.search(r'versionCode\s*=\s*(\d+)', content)
    version_name_match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
    if not version_code_match or not version_name_match:
        print("❌ Could not parse version from app/build.gradle.kts")
        sys.exit(1)
    return int(version_code_match.group(1)), version_name_match.group(1)


def version_name_to_code(version_name: str) -> int:
    parts = [int(part) for part in version_name.split(".")]
    while len(parts) < 3:
        parts.append(0)
    major, minor, patch = parts[:3]
    return major * 10000 + minor * 100 + patch


def bump_version(current_name: str, bump_type: str) -> str:
    parts = [int(part) for part in current_name.split(".")]
    while len(parts) < 3:
        parts.append(0)
    major, minor, patch = parts[:3]

    if bump_type == "major":
        major += 1
        minor = 0
        patch = 0
    elif bump_type == "minor":
        minor += 1
        patch = 0
    else:
        patch += 1

    return f"{major}.{minor}.{patch}"


def update_version(version_name: str) -> int:
    version_code = version_name_to_code(version_name)
    content = APP_BUILD_GRADLE.read_text()
    content = re.sub(r'versionCode\s*=\s*\d+', f'versionCode = {version_code}', content)
    content = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{version_name}"', content)
    APP_BUILD_GRADLE.write_text(content)
    print(f"✅ Updated version to {version_name} ({version_code})")
    return version_code


def get_keystore_credentials() -> dict[str, str]:
    props = get_local_properties()
    creds = {
        "keystore_file": props.get("KEYSTORE_FILE") or os.environ.get("KEYSTORE_FILE") or str(DEFAULT_KEYSTORE_PATH),
        "store_password": props.get("KEYSTORE_PASSWORD") or os.environ.get("KEYSTORE_PASSWORD") or "",
        "key_alias": props.get("KEY_ALIAS") or os.environ.get("KEY_ALIAS") or "",
        "key_password": props.get("KEY_PASSWORD") or os.environ.get("KEY_PASSWORD") or "",
    }

    if not creds["store_password"]:
        creds["store_password"] = input("🔑 Keystore password: ")
    if not creds["key_alias"]:
        creds["key_alias"] = input("🔑 Key alias: ")
    if not creds["key_password"]:
        creds["key_password"] = creds["store_password"]

    return creds


def find_latest_build_tool(binary_name: str) -> Path | None:
    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_root:
        for candidate in (Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk"):
            if candidate.exists():
                sdk_root = str(candidate)
                break

    if not sdk_root:
        return None

    build_tools_dir = Path(sdk_root) / "build-tools"
    if not build_tools_dir.exists():
        return None

    versions = sorted(build_tools_dir.iterdir(), reverse=True)
    for version_dir in versions:
        binary_path = version_dir / binary_name
        if binary_path.exists():
            return binary_path
    return None


def run_command(command: list[str], *, cwd: Path | None = None) -> None:
    result = subprocess.run(command, cwd=cwd, text=True)
    if result.returncode != 0:
        print(f"❌ Command failed: {' '.join(command)}")
        sys.exit(result.returncode)


def build_release() -> Path:
    print("🔨 Building release APK...")
    gradle_cmd = "./gradlew" if os.name != "nt" else "gradlew.bat"
    run_command([gradle_cmd, "assembleRelease", "--no-daemon"], cwd=PROJECT_ROOT)

    if RELEASE_APK_PATH.exists():
        print(f"✅ Built signed APK: {RELEASE_APK_PATH}")
        return RELEASE_APK_PATH
    if UNSIGNED_APK_PATH.exists():
        print(f"⚠️ Built unsigned APK: {UNSIGNED_APK_PATH}")
        return UNSIGNED_APK_PATH

    print("❌ Release APK not found after build")
    sys.exit(1)


def sign_apk(apk_path: Path, credentials: dict[str, str]) -> Path:
    keystore_path = Path(credentials["keystore_file"])
    if not keystore_path.exists():
        print(f"❌ Keystore not found: {keystore_path}")
        sys.exit(1)

    working_apk = apk_path
    zipalign = find_latest_build_tool("zipalign")
    if zipalign:
        aligned_apk = apk_path.parent / "app-release-aligned.apk"
        print("📐 Aligning APK...")
        run_command([str(zipalign), "-v", "-p", "4", str(apk_path), str(aligned_apk)])
        working_apk = aligned_apk

    signed_apk = apk_path.parent / "app-release-signed.apk"
    apksigner = find_latest_build_tool("apksigner")
    if apksigner:
        print("🔐 Signing APK with apksigner...")
        run_command([
            str(apksigner),
            "sign",
            "--ks", str(keystore_path),
            "--ks-pass", f"pass:{credentials['store_password']}",
            "--ks-key-alias", credentials["key_alias"],
            "--key-pass", f"pass:{credentials['key_password']}",
            "--out", str(signed_apk),
            str(working_apk),
        ])
    else:
        print("🔐 apksigner not found, falling back to jarsigner...")
        run_command([
            "jarsigner",
            "-sigalg", "SHA256withRSA",
            "-digestalg", "SHA-256",
            "-keystore", str(keystore_path),
            "-storepass", credentials["store_password"],
            "-keypass", credentials["key_password"],
            "-signedjar", str(signed_apk),
            str(working_apk),
            credentials["key_alias"],
        ])

    print(f"✅ Signed APK ready: {signed_apk}")
    return signed_apk


def upload_release(apk_path: Path, version_name: str, notes: str, force: bool) -> None:
    upload_script = PROJECT_ROOT / "upload_release.py"
    command = [sys.executable, str(upload_script), str(apk_path), version_name, "--notes", notes]
    if force:
        command.append("--force")
    run_command(command, cwd=PROJECT_ROOT)


def main() -> None:
    parser = argparse.ArgumentParser(description="Create a SyncPad release")
    parser.add_argument("version", nargs="?", help="Version name, for example 1.0.1")
    parser.add_argument("--bump", choices=["major", "minor", "patch"], help="Auto-bump the current version")
    parser.add_argument("--notes", default="", help="Release notes")
    parser.add_argument("--force", action="store_true", help="Mark the release as a force update")
    parser.add_argument("--skip-upload", action="store_true", help="Build/sign only, do not upload")
    parser.add_argument("--skip-build", action="store_true", help="Use the existing release APK instead of rebuilding")
    args = parser.parse_args()

    current_code, current_name = get_current_version()
    print(f"📱 Current version: {current_name} ({current_code})")

    if args.version:
        new_version = args.version
    elif args.bump:
        new_version = bump_version(current_name, args.bump)
        print(f"📈 Bumping {args.bump}: {current_name} -> {new_version}")
    else:
        new_version = bump_version(current_name, "patch")
        print(f"📈 Auto-bumping patch: {current_name} -> {new_version}")

    new_code = update_version(new_version)
    credentials = get_keystore_credentials()

    if args.skip_build:
        apk_path = RELEASE_APK_PATH if RELEASE_APK_PATH.exists() else UNSIGNED_APK_PATH
        if not apk_path.exists():
            print("❌ No existing release APK found. Remove --skip-build or build once first.")
            sys.exit(1)
    else:
        apk_path = build_release()

    if "unsigned" in apk_path.name:
        apk_path = sign_apk(apk_path, credentials)

    size_mb = apk_path.stat().st_size / (1024 * 1024)
    print(f"📦 APK ready: {apk_path.name} ({size_mb:.2f} MB)")

    if not args.skip_upload:
        upload_release(apk_path, new_version, args.notes or f"Version {new_version}", args.force)

    print(f"🎉 SyncPad v{new_version} complete")
    print(f"   Version code: {new_code}")
    print(f"   APK: {apk_path}")


if __name__ == "__main__":
    main()
