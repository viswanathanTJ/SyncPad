#!/usr/bin/env python3
"""
Migration script to push all text files from GitHub repository to Supabase.
Designed for GitHub Codespaces with 72k+ files.

Features:
- Chunk-by-chunk processing with resume capability
- Progress saved to JSON for resumability
- Reads credentials from .env file
- Handles large file counts efficiently
- Failed files copied to separate directory with error log

Usage:
    1. Create .env file with:
       SUPABASE_URL=your-supabase-url
       SUPABASE_KEY=your-supabase-anon-key
       DEVICE_ID=system
    
    2. Run:
       python migrate_blogs.py
       
    3. Resume after interruption:
       python migrate_blogs.py  # Automatically resumes
       
    4. Reset and start fresh:
       python migrate_blogs.py --reset
"""

import os
import sys
import time
import hashlib
import json
import shutil
import argparse
from pathlib import Path
from typing import List, Dict, Any, Set, Optional
from datetime import datetime

# Load .env file
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    print("Warning: python-dotenv not installed. Using environment variables only.")
    print("Install with: pip install python-dotenv")

try:
    from supabase import create_client, Client
except ImportError:
    print("Error: supabase-py is not installed.")
    print("Please install it with: pip install supabase")
    sys.exit(1)


class MigrationProgress:
    """Manages migration progress for resumability."""
    
    def __init__(self, cache_file: Path):
        self.cache_file = cache_file
        self.data = {
            'migrated_ids': set(),
            'failed': {},
            'skipped': set(),
            'last_file_index': 0,
            'total_files': 0,
            'stats': {
                'total_files': 0,
                'migrated': 0,
                'skipped': 0,
                'errors': 0
            },
            'file_list_hash': None,
            'last_updated': None
        }
        self.load()
    
    def load(self) -> None:
        """Load progress from cache file."""
        if self.cache_file.exists():
            try:
                with open(self.cache_file, 'r') as f:
                    loaded = json.load(f)
                # Convert lists back to sets
                loaded['migrated_ids'] = set(loaded.get('migrated_ids', []))
                loaded['skipped'] = set(loaded.get('skipped', []))
                self.data = loaded
                print(f"📂 Loaded progress: {len(self.data['migrated_ids'])} files already migrated")
                print(f"   Last processed index: {self.data['last_file_index']}")
            except Exception as e:
                print(f"⚠️  Warning: Could not load progress cache: {e}")
    
    def save(self) -> None:
        """Save progress to cache file atomically."""
        try:
            self.data['last_updated'] = datetime.now().isoformat()
            # Convert sets to lists for JSON serialization
            save_data = self.data.copy()
            save_data['migrated_ids'] = list(self.data['migrated_ids'])
            save_data['skipped'] = list(self.data['skipped'])
            
            temp_file = self.cache_file.with_suffix('.tmp')
            with open(temp_file, 'w') as f:
                json.dump(save_data, f, indent=2)
            temp_file.replace(self.cache_file)
        except Exception as e:
            print(f"⚠️  Warning: Could not save progress: {e}")
    
    def is_migrated(self, file_id: str) -> bool:
        return file_id in self.data['migrated_ids']
    
    def mark_migrated(self, file_id: str) -> None:
        if file_id not in self.data['migrated_ids']:
            self.data['migrated_ids'].add(file_id)
            self.data['stats']['migrated'] += 1
    
    def mark_skipped(self, file_id: str) -> None:
        if file_id not in self.data['skipped']:
            self.data['skipped'].add(file_id)
            self.data['stats']['skipped'] += 1
    
    def mark_failed(self, file_id: str, error: str) -> None:
        self.data['failed'][file_id] = error
        self.data['stats']['errors'] += 1
    
    def update_index(self, index: int) -> None:
        self.data['last_file_index'] = index
    
    def set_file_list_hash(self, hash_value: str) -> None:
        self.data['file_list_hash'] = hash_value
    
    def get_file_list_hash(self) -> Optional[str]:
        return self.data.get('file_list_hash')
    
    def reset(self) -> None:
        """Reset all progress."""
        self.data = {
            'migrated_ids': set(),
            'failed': {},
            'skipped': set(),
            'last_file_index': 0,
            'total_files': 0,
            'stats': {
                'total_files': 0,
                'migrated': 0,
                'skipped': 0,
                'errors': 0
            },
            'file_list_hash': None,
            'last_updated': None
        }
        if self.cache_file.exists():
            self.cache_file.unlink()
            print("🔄 Progress cache deleted")


class BlogMigrator:
    """Handles migration of text files to Supabase blogs table."""
    
    # Extensions to include
    TEXT_EXTENSIONS = {'.txt'}
    
    # Directories to exclude
    EXCLUDE_DIRS = {
        '.git', '.venv', 'node_modules', '__pycache__',
        'venv', 'env', 'build', 'dist', '.idea',
        '.gradle', 'android/build', 'android/.gradle',
        'migration_errors',  # Exclude error directory
    }
    
    def __init__(self, supabase_url: str, supabase_key: str, device_id: str = "system",
                 batch_size: int = 50, batch_delay: float = 0.5, smart_mode: bool = True,
                 error_dir: Path = None):
        """
        Initialize the migrator.
        
        Args:
            supabase_url: Supabase project URL
            supabase_key: Supabase anon/service key
            device_id: Device ID for all migrated blogs
            batch_size: Files per batch (smaller = more frequent saves)
            batch_delay: Delay between batches in seconds
            smart_mode: Skip files that already exist in database
            error_dir: Directory to store failed files
        """
        self.supabase: Client = create_client(supabase_url, supabase_key)
        self.device_id = device_id
        self.batch_size = batch_size
        self.batch_delay = batch_delay
        self.smart_mode = smart_mode
        self.existing_ids: Set[int] = set()
        self.error_dir = error_dir
        self.error_log_path = None
        self.consecutive_failures = 0
        self.max_consecutive_failures = 10  # Stop if this many fail in a row
    
    def sanitize_string(self, s: str) -> str:
        """Remove surrogate characters from a string to make it safe for UTF-8 encoding."""
        if isinstance(s, str):
            # Encode with surrogatepass to convert surrogates to bytes, then decode replacing errors
            return s.encode('utf-8', errors='surrogateescape').decode('utf-8', errors='replace')
        return s
    
    def setup_error_directory(self, repo_root: Path) -> None:
        """Create error directory and initialize error log."""
        if self.error_dir is None:
            self.error_dir = repo_root / 'migration_errors'
        
        # Create error directory
        self.error_dir.mkdir(parents=True, exist_ok=True)
        
        # Create files subdirectory for failed files
        (self.error_dir / 'files').mkdir(exist_ok=True)
        
        # Initialize error log path
        self.error_log_path = self.error_dir / 'error_log.txt'
        
        print(f"📁 Error directory: {self.error_dir}")
    
    def copy_failed_file(self, file_path: Path, repo_root: Path, error: str) -> None:
        """Copy a failed file to the error directory with preserved structure."""
        try:
            relative_path = file_path.relative_to(repo_root)
            dest_path = self.error_dir / 'files' / relative_path
            
            # Create parent directories
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            
            # Copy the file
            shutil.copy2(file_path, dest_path)
            
            # Append to error log (sanitize paths that may contain surrogates)
            timestamp = datetime.now().isoformat()
            safe_relative_path = self.sanitize_string(str(relative_path))
            safe_dest_path = self.sanitize_string(str(dest_path))
            with open(self.error_log_path, 'a', encoding='utf-8') as f:
                f.write(f"[{timestamp}] {safe_relative_path}\n")
                f.write(f"  Error: {error}\n")
                f.write(f"  Copied to: {safe_dest_path}\n")
                f.write("-" * 60 + "\n")
                
        except Exception as e:
            safe_file_path = self.sanitize_string(str(file_path))
            print(f"⚠️  Could not copy failed file {safe_file_path}: {e}")
    
    def generate_file_key(self, file_path: Path, repo_root: Path) -> str:
        """
        Generate a unique key for tracking a file based on its path.
        Used for progress tracking, not as database ID.
        """
        relative_path = str(file_path.relative_to(repo_root))
        # Use surrogatepass to handle filenames with invalid Unicode
        return hashlib.md5(relative_path.encode('utf-8', errors='surrogatepass')).hexdigest()
    
    def generate_title_prefix(self, title: str) -> str:
        """Generate title prefix (first 5 chars uppercase)."""
        return title[:5].upper() if title else ""
    
    def should_process_file(self, file_path: Path, repo_root: Path) -> bool:
        """Check if a file should be processed."""
        try:
            relative_path = file_path.relative_to(repo_root)
            for part in relative_path.parts:
                if part in self.EXCLUDE_DIRS:
                    return False
        except ValueError:
            return False
        
        if file_path.suffix.lower() not in self.TEXT_EXTENSIONS:
            return False
        
        if not file_path.is_file():
            return False
        
        return True
    
    def read_file_content(self, file_path: Path) -> Optional[str]:
        """Read file content with encoding fallback and surrogate handling."""
        # First try UTF-8 with surrogateescape to handle any invalid sequences
        try:
            with open(file_path, 'r', encoding='utf-8', errors='surrogateescape') as f:
                content = f.read()
            # Immediately sanitize to remove surrogates
            return self.sanitize_string(content)
        except Exception:
            pass
        
        # Fallback encodings
        for encoding in ['latin-1', 'cp1252']:
            try:
                with open(file_path, 'r', encoding=encoding) as f:
                    content = f.read()
                return self.sanitize_string(content)
            except Exception:
                continue
        
        return None
    
    def create_blog_from_file(self, file_path: Path, repo_root: Path) -> Optional[Dict[str, Any]]:
        """Create a blog dictionary from a file (without ID - let DB auto-generate)."""
        content = self.read_file_content(file_path)
        if content is None:
            return None
        
        # Use filename as title (without extension)
        title = file_path.stem
        
        # Get timestamps
        stat = file_path.stat()
        created_at = int(stat.st_ctime * 1000)
        updated_at = int(stat.st_mtime * 1000)
        
        # Don't include 'id' - let Supabase BIGSERIAL auto-generate it
        # Sanitize content and title to remove surrogate characters
        return {
            'title': self.sanitize_string(title),
            'content': self.sanitize_string(content),
            'title_prefix': self.generate_title_prefix(self.sanitize_string(title)),
            'created_at': created_at,
            'updated_at': updated_at,
            'device_id': self.device_id,
        }
    
    def fetch_existing_ids(self) -> None:
        """Fetch all existing blog IDs from Supabase."""
        if not self.smart_mode:
            return
        
        print("🔍 Fetching existing blog IDs from database...")
        try:
            batch_size = 1000
            offset = 0
            
            while True:
                result = self.supabase.table('blogs').select('id').range(
                    offset, offset + batch_size - 1
                ).execute()
                
                if not result.data:
                    break
                
                for row in result.data:
                    self.existing_ids.add(row['id'])
                
                if len(result.data) < batch_size:
                    break
                
                offset += batch_size
            
            print(f"✓ Found {len(self.existing_ids)} existing blogs in database")
        except Exception as e:
            error_str = str(e)
            # Check for authentication errors - these are fatal
            if '401' in error_str or 'Invalid API key' in error_str:
                print(f"❌ Authentication failed: {e}")
                print("   Please check your SUPABASE_URL and SUPABASE_KEY in .env file")
                raise SystemExit(1)
            print(f"⚠️  Could not fetch existing IDs: {e}")
            print("   Continuing without duplicate detection...")
    
    def find_all_text_files(self, repo_root: Path) -> List[Path]:
        """Find all text files in the repository."""
        print(f"🔍 Scanning for text files in: {repo_root}")
        text_files = []
        
        for file_path in repo_root.rglob('*'):
            if self.should_process_file(file_path, repo_root):
                text_files.append(file_path)
        
        # Sort for consistent ordering (important for resume)
        text_files.sort()
        return text_files
    
    def compute_file_list_hash(self, files: List[Path]) -> str:
        """Compute hash of file list for change detection."""
        paths_str = '\n'.join(str(f) for f in files)
        # Use surrogatepass to handle filenames with invalid Unicode characters
        return hashlib.md5(paths_str.encode('utf-8', errors='surrogatepass')).hexdigest()
    
    def migrate_single_file(self, file_path: Path, repo_root: Path, progress: MigrationProgress) -> bool:
        """Migrate a single file to Supabase."""
        # Generate file key for progress tracking
        file_key = self.generate_file_key(file_path, repo_root)
        
        # Skip if already migrated in this run
        if progress.is_migrated(file_key):
            return True
        
        try:
            blog = self.create_blog_from_file(file_path, repo_root)
            if not blog:
                error_msg = "Could not read file content"
                progress.mark_failed(str(file_path.relative_to(repo_root)), error_msg)
                self.copy_failed_file(file_path, repo_root, error_msg)
                return False
            
            # Insert to Supabase (let DB auto-generate ID)
            result = self.supabase.table('blogs').insert(blog).execute()
            
            if result.data:
                progress.mark_migrated(file_key)
                self.consecutive_failures = 0  # Reset on success
                return True
            else:
                error_msg = "Insert returned no data"
                progress.mark_failed(file_key, error_msg)
                self.copy_failed_file(file_path, repo_root, error_msg)
                self.consecutive_failures += 1
                return False
                
        except Exception as e:
            error_msg = str(e)
            progress.mark_failed(str(file_path.relative_to(repo_root)), error_msg)
            self.copy_failed_file(file_path, repo_root, error_msg)
            self.consecutive_failures += 1
            return False
    
    def migrate_all(self, repo_root: Path, progress: MigrationProgress) -> None:
        """Migrate all text files with chunked processing and resume support."""
        print(f"\n{'='*60}")
        print(f"🚀 Starting Migration")
        print(f"{'='*60}")
        print(f"Repository: {repo_root}")
        print(f"Device ID: {self.device_id}")
        print(f"Batch size: {self.batch_size}")
        print(f"Smart mode: {self.smart_mode}")
        print(f"{'='*60}\n")
        
        # Setup error directory
        self.setup_error_directory(repo_root)
        
        # Find all text files
        all_files = self.find_all_text_files(repo_root)
        current_hash = self.compute_file_list_hash(all_files)
        
        print(f"📁 Found {len(all_files)} text files")
        
        # Check if file list changed since last run
        if progress.get_file_list_hash() and progress.get_file_list_hash() != current_hash:
            print("⚠️  File list has changed since last run!")
            print("   Consider using --reset if files were added/removed")
        
        progress.set_file_list_hash(current_hash)
        progress.data['total_files'] = len(all_files)
        progress.data['stats']['total_files'] = len(all_files)
        
        # Fetch existing IDs for smart mode
        if self.smart_mode:
            self.fetch_existing_ids()
        
        # Resume from last index
        start_index = progress.data['last_file_index']
        if start_index > 0:
            print(f"\n📂 Resuming from file {start_index + 1}/{len(all_files)}")
        
        # Process files in batches
        total_batches = (len(all_files) - start_index + self.batch_size - 1) // self.batch_size
        current_batch = 0
        
        try:
            for i in range(start_index, len(all_files)):
                file_path = all_files[i]
                
                # Process file
                success = self.migrate_single_file(file_path, repo_root, progress)
                
                # Check for too many consecutive failures (likely systemic issue)
                if self.consecutive_failures >= self.max_consecutive_failures:
                    print(f"\n❌ Too many consecutive failures ({self.consecutive_failures})!")
                    print("   This usually indicates a systemic issue (invalid API key, network, etc.)")
                    print("   Please check your credentials and try again.")
                    progress.save()
                    raise SystemExit(1)
                
                # Update progress index
                progress.update_index(i + 1)
                
                # Show progress and save every batch
                files_in_current_batch = (i - start_index) % self.batch_size + 1
                
                if files_in_current_batch == self.batch_size or i == len(all_files) - 1:
                    current_batch += 1
                    percentage = ((i + 1) / len(all_files)) * 100
                    
                    # Save progress
                    progress.save()
                    
                    print(f"📦 Batch {current_batch}/{total_batches} | "
                          f"File {i + 1}/{len(all_files)} ({percentage:.1f}%) | "
                          f"Migrated: {progress.data['stats']['migrated']} | "
                          f"Skipped: {progress.data['stats']['skipped']} | "
                          f"Errors: {progress.data['stats']['errors']}")
                    
                    # Delay between batches (except last)
                    if i < len(all_files) - 1 and self.batch_delay > 0:
                        time.sleep(self.batch_delay)
        
        except KeyboardInterrupt:
            print("\n\n⚠️  Migration interrupted by user!")
            progress.save()
            print(f"Progress saved. Run again to resume from file {progress.data['last_file_index'] + 1}")
            raise
        
        # Final save
        progress.save()
        
        # Print summary
        print(f"\n{'='*60}")
        print("📊 Migration Summary")
        print(f"{'='*60}")
        print(f"  Total files found: {progress.data['stats']['total_files']}")
        print(f"  Successfully migrated: {progress.data['stats']['migrated']}")
        print(f"  Skipped (existing): {progress.data['stats']['skipped']}")
        print(f"  Errors: {progress.data['stats']['errors']}")
        print(f"{'='*60}")
        
        # Report error directory if there were errors
        if progress.data['stats']['errors'] > 0:
            print(f"\n⚠️  Failed files copied to: {self.error_dir / 'files'}")
            print(f"   Error details in: {self.error_log_path}")


def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description='Migrate text files to Supabase blogs table',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Run migration (auto-resumes if interrupted)
  python migrate_blogs.py
  
  # Custom batch size (smaller = more frequent saves)
  python migrate_blogs.py --batch-size 25
  
  # Force mode (re-upload all, don't skip existing)
  python migrate_blogs.py --force
  
  # Reset and start fresh
  python migrate_blogs.py --reset
        """
    )
    
    parser.add_argument('--batch-size', type=int, default=50,
                       help='Files per batch (default: 50)')
    parser.add_argument('--batch-delay', type=float, default=0.5,
                       help='Delay between batches in seconds (default: 0.5)')
    parser.add_argument('--force', action='store_true',
                       help='Force mode: re-upload all files, do not skip existing')
    parser.add_argument('--reset', action='store_true',
                       help='Reset progress and start fresh')
    parser.add_argument('--debug', action='store_true',
                       help='Show debug info including loaded credentials')
    parser.add_argument('repo_path', nargs='?', default='.',
                       help='Repository root path (default: current directory)')
    
    args = parser.parse_args()
    
    # Get Supabase credentials from environment/.env
    supabase_url = os.getenv('SUPABASE_URL', '').strip()
    supabase_key = os.getenv('SUPABASE_KEY', '').strip()
    device_id = os.getenv('DEVICE_ID', 'system').strip()
    
    # Remove any quotes that might be in the values
    supabase_url = supabase_url.strip('"').strip("'")
    supabase_key = supabase_key.strip('"').strip("'")
    
    # Debug: show what we loaded
    if args.debug:
        print(f"🔧 Debug: SUPABASE_URL = {supabase_url[:50]}..." if len(supabase_url) > 50 else f"🔧 Debug: SUPABASE_URL = {supabase_url}")
        print(f"🔧 Debug: SUPABASE_KEY = {supabase_key[:20]}...{supabase_key[-10:]}" if len(supabase_key) > 30 else f"🔧 Debug: SUPABASE_KEY = {supabase_key}")
        print(f"🔧 Debug: DEVICE_ID = {device_id}")
    
    # Validate URL format
    if supabase_url and not supabase_url.startswith('http'):
        print(f"⚠️  Warning: SUPABASE_URL doesn't look like a URL: {supabase_url}")
    
    # Validate key format (should be a JWT starting with eyJ)
    if supabase_key and not supabase_key.startswith('eyJ'):
        print(f"⚠️  Warning: SUPABASE_KEY doesn't look like a JWT token")
        print(f"   Expected format: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
        print(f"   Got: {supabase_key[:30]}...")
    
    if not supabase_url or not supabase_key:
        print("❌ Error: Missing Supabase credentials")
        print("\nPlease create a .env file with:")
        print("  SUPABASE_URL=your-supabase-url")
        print("  SUPABASE_KEY=your-supabase-anon-key")
        print("  DEVICE_ID=system  # optional")
        sys.exit(1)
    
    # Get repository root
    repo_root = Path(args.repo_path).resolve()
    if not repo_root.exists():
        print(f"❌ Error: Directory not found: {repo_root}")
        sys.exit(1)
    
    # Initialize progress tracker
    cache_file = repo_root / '.migration_progress.json'
    progress = MigrationProgress(cache_file)
    
    # Reset if requested
    if args.reset:
        print("🔄 Resetting migration progress...")
        progress.reset()
    
    # Create migrator and run
    try:
        migrator = BlogMigrator(
            supabase_url=supabase_url,
            supabase_key=supabase_key,
            device_id=device_id,
            batch_size=args.batch_size,
            batch_delay=args.batch_delay,
            smart_mode=not args.force
        )
        
        migrator.migrate_all(repo_root, progress)
        
        if progress.data['stats']['errors'] > 0:
            sys.exit(1)
        
    except KeyboardInterrupt:
        print("\nMigration can be resumed by running the script again.")
        sys.exit(1)
    except Exception as e:
        print(f"\n❌ Fatal error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == '__main__':
    main()
