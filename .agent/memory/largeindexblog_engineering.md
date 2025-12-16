# LargeIndexBlog - Full Engineering Specification

## Project Overview
**Package:** `com.example.largeindexblog`
**Min SDK:** API 33 (Android 13)
**Build System:** Gradle Kotlin DSL with Version Catalog

## Architecture
```
┌─────────────────────────────────────────────────────────────────┐
│                           UI Layer                               │
│  HomeScreen │ DetailScreen │ AddBlogScreen │ SettingsScreen     │
│  ↓ Compose + Paging 3                                           │
├─────────────────────────────────────────────────────────────────┤
│                        ViewModel Layer                           │
│  BlogListVM │ BlogDetailVM │ AddBlogVM │ SettingsVM             │
│  ↓ StateFlow + sealed UiState/SyncState/IndexState              │
├─────────────────────────────────────────────────────────────────┤
│                       Repository Layer                           │
│  BlogRepository │ PrefixIndexRepository │ SyncRepository        │
│  SettingsRepository (DataStore)                                  │
├─────────────────────────────────────────────────────────────────┤
│                         Data Layer                               │
│  Room DB: blogs │ prefix_index │ sync_meta                      │
│  PagingSource (cursor-based) │ PrefixIndexBuilder               │
├─────────────────────────────────────────────────────────────────┤
│                         Sync Layer                               │
│  SyncManager (placeholder for Supabase integration)             │
└─────────────────────────────────────────────────────────────────┘
```

## Database Schema

### blogs table
| Column | Type | Description |
|--------|------|-------------|
| id | Long (PK) | Auto-generated |
| title | String | Blog title |
| content | String? | Blog content (nullable) |
| title_prefix | String | First 5 chars of title, uppercase |
| created_at | Long | Timestamp |
| updated_at | Long | Timestamp |
| device_id | String | Device identifier |

**Indices:** `(title_prefix, title, id)` for cursor paging

### prefix_index table (local-only)
| Column | Type | Description |
|--------|------|-------------|
| prefix | String (PK) | Prefix string |
| depth | Int (PK) | Prefix depth |
| count | Int | Number of blogs |
| first_blog_id | Long | For quick jump |

### sync_meta table
| Column | Type | Description |
|--------|------|-------------|
| key | String (PK) | Metadata key |
| value | String? | Metadata value |

## Key Features

### Cursor-Based Paging (No OFFSET)
- Uses `(title_prefix, title, id)` as cursor
- Stable at 200k+ rows
- Loads only `id, title, created_at` for list (no content)

### Prefix Index
- `MAX_DEPTH` configurable (default 5)
- `EXPANSION_THRESHOLD` = 50
- Full rebuild and partial update support
- Background processing with transactions

### Sync
- `SyncResult` data class with downloaded/uploaded/deleted counts
- Incremental sync: changes since `last_sync_time`
- Hard sync: clear and re-download
- Status bar shows: `↓X downloaded ↑Y uploaded ✕Z deleted`

### Settings (DataStore)
- Theme: light/dark/system
- Font size: 12-32sp
- MAX_DEPTH: 1-10
- Show bottom index: boolean

## Key Files

### Data Layer
- `data/entity/BlogEntity.kt` - Blog table
- `data/entity/PrefixIndexEntity.kt` - Index table
- `data/entity/SyncMetaEntity.kt` - Sync metadata
- `data/dao/BlogDao.kt` - CRUD + paging queries
- `data/paging/BlogPagingSource.kt` - Cursor-based paging
- `data/index/PrefixIndexBuilder.kt` - Index builder

### Repository Layer
- `repository/BlogRepository.kt` - Blog operations
- `repository/PrefixIndexRepository.kt` - Index operations
- `repository/SyncRepository.kt` - Sync metadata
- `repository/SettingsRepository.kt` - DataStore preferences

### ViewModel Layer
- `ui/viewmodel/BlogListViewModel.kt` - Home screen
- `ui/viewmodel/BlogDetailViewModel.kt` - Detail screen
- `ui/viewmodel/AddBlogViewModel.kt` - Add/edit
- `ui/viewmodel/SettingsViewModel.kt` - Settings

### UI Layer
- `ui/screen/HomeScreen.kt` - List + sidebar + sync button
- `ui/screen/DetailScreen.kt` - View blog
- `ui/screen/AddBlogScreen.kt` - Create/edit
- `ui/screen/SettingsScreen.kt` - Settings
- `ui/components/AlphabetSidebar.kt` - A-Z navigation

### Sync Layer
- `sync/SyncManager.kt` - Sync operations + SyncResult

## Dependency Injection (Hilt)
- `di/DatabaseModule.kt` - Room DAOs
- `di/AppModule.kt` - SyncManager, PrefixIndexBuilder

## Supabase Integration
SQL schema in `supabase.sql` with:
- Matching table structure
- Auto-update triggers
- RPC functions for sync
- RLS templates

## Build Commands
```bash
# Set JAVA_HOME for Java 17
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.17/libexec/openjdk.jdk/Contents/Home

# Build
./gradlew clean build

# Compile only
./gradlew compileDebugKotlin
```

## Secrets Configuration
Add to `local.properties`:
```properties
SYNC_API_KEY=your_api_key
SYNC_BASE_URL=https://your-supabase-url.supabase.co
```

Exposed via `BuildConfig.SYNC_API_KEY` and `BuildConfig.SYNC_BASE_URL`.
