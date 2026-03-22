# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Required on macOS with Homebrew before building
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK (minified + shrunk)
./gradlew compileDebugKotlin     # Fast compile check (no APK)
./gradlew clean build            # Full clean build
```

No tests are currently written, though `AndroidJUnitRunner` is configured.

## Secrets

Supabase credentials and keystore config go in `local.properties` (see `local.properties.example`). These are exposed via `BuildConfig.SYNC_API_KEY` and `BuildConfig.SYNC_BASE_URL`. If missing, sync is disabled gracefully (`isSyncConfigured()` check).

## Architecture

Single-activity Jetpack Compose app following MVVM with Hilt DI. Five distinct layers:

```
Compose Screens → ViewModels (StateFlow) → Repositories (Result<T>) → Room DB + Paging 3 → SyncManager + SupabaseApi
```

**Key packages** (`com.viswa2k.syncpad`):
- `data/` — Room entities (`BlogEntity`, `PrefixIndexEntity`, `SyncMetaEntity`), DAOs, `AppDatabase`, cursor-based `BlogPagingSource`, `PrefixIndexBuilder`
- `di/` — Hilt modules (`AppModule`, `DatabaseModule`), all singletons via `@Singleton` in `SingletonComponent`
- `repository/` — `BlogRepository`, `PrefixIndexRepository`, `SyncRepository`, `SettingsRepository` (DataStore Preferences)
- `sync/` — `SyncManager` (app-scoped coroutine scope with `SupervisorJob`), `SupabaseApi` (raw OkHttp, not official SDK), `BlogDto`
- `ui/screen/` — `HomeScreen`, `DetailScreen`, `AddBlogScreen`, `SettingsScreen`, `SearchScreen`
- `ui/viewmodel/` — One ViewModel per screen, all `@HiltViewModel`
- `ui/state/` — Sealed classes: `UiState<T>` (Loading/Success/Error), `SyncState`, `IndexState`
- `ui/components/` — `AlphabetSidebar`, `HierarchicalIndexSidebar`, `QuickNavigationPopup`

**Navigation** — Flat `NavHost` with routes: `home`, `detail/{blogId}`, `add`, `edit/{blogId}`, `settings`, `search`.

## Key Design Decisions

- **Cursor-based paging**: `BlogPagingSource` uses `(title_prefix, title, id)` as cursor instead of OFFSET for stability at 200k+ rows. Page size is 50 items.
- **Prefix index**: Local-only derived table for hierarchical alphabet navigation. Single letters expand to sub-prefixes when count exceeds 50. Max depth configurable 1–10. Rebuilt via `PrefixIndexBuilder` (partial update if ≤100 affected prefixes, else full rebuild).
- **Sync**: Incremental sync via Supabase REST API (changes since `last_sync_time`). Resume-on-kill via `sync_meta` table persisting progress (`sync_in_progress`, `sync_last_id`). Streaming JSON parsing (Gson `JsonReader`) to avoid OOM. Batch inserts of 500 per transaction. Soft delete on server, hard delete locally during sync.
- **Repository pattern**: All DB operations return `Result<T>` — no exceptions escape. All use `withContext(Dispatchers.IO)`. `BlogRepository` exposes a `MutableSharedFlow<Unit>` (`dataChanged`) for reactive refresh across ViewModels.
- **Database**: Room v2, `fallbackToDestructiveMigration()` (acceptable since data resyncs from server). Schema export enabled.
- **Logging**: All logging through `AppLogger` utility (prefixes tags with `"SyncPad:"`).

## Database Schema

Three tables: `blogs` (main content, indexed on `title_prefix`, `created_at`, `updated_at`, `is_deleted`), `prefix_index` (local-only navigation, composite PK: `prefix + depth`), `sync_meta` (key-value store for sync state).

Server schema defined in `supabase.sql`, with migrations in `supabase_soft_delete.sql` and `supabase_recycle_bin.sql`.

## Build Config

- **SDK**: minSdk 33, compileSdk/targetSdk 35
- **Java**: VERSION_21
- **Kotlin**: 2.0.21, KSP 2.0.21-1.0.28
- **Dependency versions**: Centralized in `gradle/libs.versions.toml`
- **ProGuard**: Keeps Room entities, Hilt, Gson `@SerializedName` fields, OkHttp
