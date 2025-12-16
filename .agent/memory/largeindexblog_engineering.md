# SyncPad - Full Engineering Specification

## Project Overview
**App Name:** SyncPad
**Package:** `com.viswa2k.syncpad`
**Min SDK:** API 33 (Android 13)
**Build System:** Gradle Kotlin DSL with Version Catalog

## Architecture
```
┌─────────────────────────────────────────────────────────────────┐
│                           UI Layer                               │
│  HomeScreen │ DetailScreen │ AddBlogScreen │ SettingsScreen     │
│  SearchScreen                                                    │
│  ↓ Compose + Paging 3                                           │
├─────────────────────────────────────────────────────────────────┤
│                        ViewModel Layer                           │
│  BlogListVM │ BlogDetailVM │ AddBlogVM │ SettingsVM │ SearchVM  │
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
│  SyncManager + SupabaseApi (OkHttp + Gson)                      │
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

### Hierarchical Index Sidebar
- **Single tap** → filters to that prefix (e.g., "A")
- **Long press** → opens drill-down popup (if count > 50)
- Popup shows child prefixes (AA, AB, AC...) with counts
- Navigate up/down in popup
- Only shows prefixes with items (no empty entries)

### Section Headers in List View
- Shows first character with count when unfiltered
- Shows current filter prefix with actual count when filtered
- Clickable header opens drill-down popup (if count > 50)
- Uses `getCountByPrefix()` for accurate database count
- Arrow (▸) indicator when drill-down available

### Cursor-Based Paging (No OFFSET)
- Uses `(title_prefix, title, id)` as cursor
- Stable at 200k+ rows
- Loads only `id, title, created_at, title_prefix` for list (no content)

### Prefix Index
- `MAX_DEPTH` = 5 (configurable 1-5)
- `EXPANSION_THRESHOLD` = 50 items
- Full rebuild and partial update support
- Background processing with transactions

### Sync with Supabase
- Real HTTP sync via `SupabaseApi` (OkHttp + Gson)
- `SyncResult` data class with downloaded/uploaded/deleted counts
- Incremental sync: changes since `last_sync_time`
- Hard sync: clear and re-download
- Status bar shows: `↓X downloaded ↑Y uploaded ✕Z deleted`
- Network connectivity check before sync
- Batched uploads (100 items per batch)

### Copy to Clipboard
- Copy icon in DetailScreen
- Copies title + content to clipboard
- Snackbar confirmation

### Auto-refresh on Return
- HomeScreen uses lifecycle observer
- Calls `refreshList()` on `ON_RESUME`
- Newly created/edited blogs appear immediately

### Settings (DataStore)
- Theme: light/dark/system
- Font size: 12-32sp
- MAX_DEPTH: 1-5 (slider)

## Key Files

### Data Layer
- `data/entity/BlogEntity.kt` - Blog table + generateTitlePrefix()
- `data/entity/PrefixIndexEntity.kt` - Index table
- `data/entity/SyncMetaEntity.kt` - Sync metadata
- `data/dao/BlogDao.kt` - CRUD + paging + getCountByPrefix + getChildPrefixCounts
- `data/paging/BlogPagingSource.kt` - Cursor-based paging
- `data/index/PrefixIndexBuilder.kt` - Index builder
- `data/model/BlogListItem.kt` - Lightweight list item with titlePrefix

### Repository Layer
- `repository/BlogRepository.kt` - Blog operations + getCountByPrefix + getChildPrefixCounts
- `repository/PrefixIndexRepository.kt` - Index operations
- `repository/SyncRepository.kt` - Sync metadata
- `repository/SettingsRepository.kt` - DataStore preferences

### ViewModel Layer
- `ui/viewmodel/BlogListViewModel.kt` - Home screen + getCountByPrefix + getChildPrefixCounts
- `ui/viewmodel/BlogDetailViewModel.kt` - Detail screen
- `ui/viewmodel/AddBlogViewModel.kt` - Create/edit + auto-title + sync after save
- `ui/viewmodel/SettingsViewModel.kt` - Settings
- `ui/viewmodel/SearchViewModel.kt` - Search with debounce

### UI Layer
- `ui/screen/HomeScreen.kt` - List + HierarchicalIndexSidebar + sync button + section headers
- `ui/screen/DetailScreen.kt` - View blog + copy icon
- `ui/screen/AddBlogScreen.kt` - Create/edit + paste icon + auto-save on back
- `ui/screen/SettingsScreen.kt` - Settings
- `ui/screen/SearchScreen.kt` - Search with filters
- `ui/components/HierarchicalIndexSidebar.kt` - Sidebar with drill-down popup
- `ui/components/AlphabetSidebar.kt` - Legacy A-Z navigation

### Sync Layer
- `sync/SyncManager.kt` - Sync operations + SyncResult + network check
- `sync/SupabaseApi.kt` - HTTP client for Supabase REST API

### Navigation
- `ui/navigation/Navigation.kt` - NavHost with routes

## Dependency Injection (Hilt)
- `di/DatabaseModule.kt` - Room DAOs
- `di/AppModule.kt` - SyncManager, PrefixIndexBuilder, SupabaseApi

## Supabase Integration
SQL schema in `supabase.sql` with:
- Matching table structure
- Auto-update triggers
- Seed data generation functions
- RLS templates (commented)

## Build Commands
```bash
# Set JAVA_HOME for Java 17
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.17/libexec/openjdk.jdk/Contents/Home

# Build
./gradlew clean build

# Compile only
./gradlew compileDebugKotlin --no-daemon
```

## Secrets Configuration
Add to `local.properties`:
```properties
SYNC_API_KEY=your_supabase_anon_key
SYNC_BASE_URL=https://your-project-id.supabase.co
```
**Important:** SYNC_BASE_URL must include `https://` scheme.

Exposed via `BuildConfig.SYNC_API_KEY` and `BuildConfig.SYNC_BASE_URL`.

## User Preferences & Design Decisions

1. **Section headers** only show drill-down if count > 50 (EXPANSION_THRESHOLD)
2. **Sidebar** shows only prefix letters (no counts)
3. **Popup** only shows prefixes with items (filters out zero-count)
4. **Max prefix depth** capped at 5 characters
5. **Title prefix** always stored as 5-character uppercase
6. **List refresh** on screen resume for immediate updates
7. **Copy button** in detail screen copies title + content
