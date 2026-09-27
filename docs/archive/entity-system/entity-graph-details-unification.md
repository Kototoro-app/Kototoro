# Entity Graph — DetailsScreen Unification Progress

> [!WARNING]
> Archived: the entity graph and work ownership model was removed in September 2026, so this page
> does not describe the current app. See [Entity System (Archived)](./index.md).

## Overview

This document tracks the progress of unifying the Kototoro detail page architecture.
The original plan (see `entity-graph-implementation-plan.md`) envisioned three parallel
detail modes converging to two. This work goes further: it converges all three into a
single `DetailsActivity`/`DetailsScreen` entry point, using a sealed `DetailsOrigin`
argument to distinguish Local, EntityGraph, and TrackingItem entries.

## Architecture Decision Update

The original plan recommended keeping `EntityDetailsActivity` as a separate shell.
During implementation it became clear that:

- `DetailsScreen` already has the richest UI (hero backdrop, panorama cover, chapter
  pager, translation, favorites, history integration)
- duplicating that surface into a second Activity would create ongoing maintenance
  burden
- the "Instant First Paint" pattern (show whatever data we have immediately, resolve
  the full graph in background) eliminates the UX risk of merging

**Decision**: merge all detail entry points into the existing `DetailsActivity` +
`DetailsScreen`, extending `DetailsViewModel` with entity-graph and tracking awareness.

## Phases and Progress

### Phase 1–3: Entity Graph Core (DONE — prior work)

- [x] Room schema: `entity`, `entity_binding`, `relation` tables
- [x] DAO + Repository for graph CRUD
- [x] Tracking-to-entity ingestion pipeline
- [x] Relation creation for WORK ↔ CHARACTER, WORK ↔ PERSON edges
- [x] Cross-site binding matcher with auto-bind thresholds
- [x] `EntityGraphSourceAdapter` contract

### Phase 4: Entity Detail Shell (DONE — prior work)

- [x] `EntityDetailsActivity` / `EntityDetailsViewModel` (standalone, now deprecated)
- [x] `PeripheralEntityScreen` / `UnifiedEntityScreen` composables (now deprecated)
- [x] Router support for `openEntityDetails(entityId)`

### Phase 5: DetailsScreen Unification (DONE)

#### 5.1 Routing & DetailsOrigin

- [x] Created `DetailsOrigin` sealed interface with four variants:
  - `LocalMangaId(mangaId: Long)`
  - `LocalMangaContent(parcelableContent: ParcelableContent)`
  - `EntityGraph(entityId: Long)`
  - `TrackingItem(serviceId: String, remoteId: Long, urlHint: String?)`
- [x] Updated `AppRouter.openEntityDetails()` → routes through unified `detailsIntent`
- [x] Updated `AppRouter.openTrackingSiteDetails()` → routes through unified `detailsIntent`
- [x] Added `AppRouter.detailsIntent(Context, Content)` overload for backwards
      compatibility with background components (notifications, suggestions, etc.)
- [x] `DetailsActivity` parses `DetailsOrigin` from intent extras

#### 5.2 ViewModel Convergence

- [x] `DetailsViewModel` accepts `DetailsOrigin` in `init` block
- [x] Instant First Paint for EntityGraph: creates synthetic `Content` from entity
      `primaryName` immediately, populates `mangaDetails`
- [x] Instant First Paint for TrackingItem: reads cached `TrackingSiteItemDetails`,
      creates synthetic `Content`, populates `mangaDetails`
- [x] Background resolution for EntityGraph:
  - Fetches entity bindings → resolves local manga ID → updates `activeMangaIdFlow`
  - Fetches relations → maps to `EntityRelationSection` / `EntityRelationItem`
  - Updates `entityRelationSections` state
- [x] Background resolution for TrackingItem:
  - Fetches remote details via `TrackingSiteDiscoveryService.getDetails()`
  - Caches result via `TrackingSiteCacheRepository`
  - Resolves tracking links → updates `activeMangaIdFlow` if bound
- [x] `activeMangaIdFlow` drives reactive re-binding of history, favorites, stats

#### 5.3 UI Integration

- [x] `DetailsScreen.kt` accepts `entityRelationSections` state parameter
- [x] `EntityRelationCarousels` composable injected into scrollable content
- [x] `EntityRelationCard` composable renders individual relation items
- [x] All Compose imports resolved (LazyRow, items, FontWeight, TextAlign, etc.)

### Phase 6: Legacy Cleanup (DONE)

- [x] Removed `TrackingSiteDetailsActivity.kt`
- [x] Removed `TrackingSiteDetailsViewModel.kt`
- [x] Removed `TrackingSiteDetailsFragment.kt`
- [x] Removed `UnifiedEntityScreen.kt`
- [x] Removed `EntityDetailsActivity.kt`
- [x] Removed `EntityDetailsViewModel.kt`
- [x] Removed `PeripheralEntityScreen.kt`
- [x] Removed AndroidManifest entries for both legacy Activities
- [x] Removed unused imports from `AppRouter.kt`
- [x] Extracted `EntityRelationSection` / `EntityRelationItem` to standalone
      `EntityRelationModels.kt`
- [x] Extracted `LocalSearchState` to standalone `LocalSearchState.kt`
- [x] Verify clean compile after extraction (`./gradlew :app:compileDebugKotlin --no-daemon`)
- [x] Confirm `TrackingSiteDetailsScreen.kt` is already absent from the source tree
- [x] Confirm `TrackingLocalSourcesPanel.kt` is already absent from the source tree
- [x] Audit remaining references to deleted types
- [x] Remove stale `AndroidManifest.xml` declarations for deleted legacy Activities

### Phase 7: Refinement (DONE)

- [x] Style `EntityRelationCarousels` to match the existing DetailsScreen design
      language (glassmorphism cards, cover art thumbnails, section-level visual grouping)
- [x] Add "Active Local Source" switcher when multiple sources are bound to entity
- [x] Wire `onEntityClick` in carousels to `AppRouter.openEntityDetails()`
- [x] Add cover art / thumbnail display to `EntityRelationCard` using entity bindings
- [x] Handle entity-graph chapter source state (show current local chapter source or unavailable hint)
- [x] Performance: debounce relation section updates to avoid UI flicker

### Phase 8: Cross-Source Unification (IN PROGRESS)

This phase extends the unified `DetailsScreen` into the actual aggregation surface for
the entity system. The key distinction is:

- `entityId` is the aggregate root for one logical work
- local source bindings and tracking-site bindings are only source-level projections
- reading/playback state remains source-aware, while details/history/favorites/updates
  gradually move toward entity-aware aggregation

#### 8.1 Aggregate State Model

- `entityId` becomes the canonical identity for "same work" decisions
- `mangaId` remains a concrete local-source binding, not the cross-source identity
- tracking `remoteId` remains site-local, not globally canonical
- lists such as favorites/history/updates/subscriptions should eventually render
  an entity-aggregated row while retaining per-source update channels underneath

#### 8.2 Metadata vs Reading/Playback Semantics

- `metadata source` controls descriptive metadata, tracking-origin sections, relation
  expansion, tracking-only episode lists, comments/reviews, and browser navigation
- `reading/playback source` controls real consumable chapters/episodes, reader/player
  entry, thumbnails, bookmarks, and actual progress state
- if metadata source is a local source, metadata and reading chapters may collapse to
  the same effective source
- if metadata source is a tracking site, its episode list is treated as browse-only
  metadata, not as reader/player content

#### 8.3 Tracking-to-Tracking Unification

- different tracking sites for the same work must converge to one `entityId`
- MALSync should be used as a high-confidence bridge between tracking sites, not as
  the only source of truth
- for video/anime content, a local offline bridge index may be used as the first and
  fastest cross-site resolution layer before online fallback
- recommended identity resolution order:
  1. same tracking service + same `remoteId`
  2. video-only local offline bridge (Anime Offline Database or equivalent)
  3. MALSync bridge to the same canonical tracking target
  4. already attached to the same `entityId`
  5. strong title/alias/year/type/episode-count similarity

#### 8.3.1 Offline Anime Bridge

- the offline anime bridge is intentionally scoped to video/anime content
- it is used for:
  - fast local title-to-tracking candidate lookup for video sources
  - tracking-to-tracking ID resolution for anime entities without immediate network roundtrips
- it is not the canonical metadata source for detail rendering
- it should be updated in background after app startup, with visible foreground
  notification progress when a new package is downloaded
- a compact app-local index should be generated from the downloaded dataset so runtime
  lookup stays cheap

**Current implementation status**

- [x] `AnimeOfflineRepository` added and wired for:
  - GitHub latest-release check
  - local JSON asset download
  - compact in-app index generation
  - `service + remoteId -> cross-site mapping` resolution
  - local video title -> tracking candidate lookup
- [x] `AnimeOfflineUpdateWorker` runs after app startup and shows foreground
      notification progress when downloading an updated dataset
- [x] `Services` settings page now exposes current Anime Offline status and a manual
      force-refresh entry point
- [ ] `.zst` / compressed asset support is still pending; current implementation
      prefers plain `.json` assets for a stable first version

#### 8.3.2 MALSync Boundary

- MALSync remains the global fallback bridge across supported tracking services
- unlike the offline anime bridge, MALSync is not limited to startup-downloaded local
  data and can continue to resolve mappings dynamically online
- if the offline anime bridge does not provide a mapping, the system should continue
  with MALSync and then fuzzy matching

**Current implementation status**

- [x] `MALSyncMappingRepository` is now part of the entity resolution chain
- [x] `EntityGraphRepository.resolveOrCreateEntity(...)` now resolves in this order:
  1. existing binding
  2. Anime Offline bridge
  3. MALSync bridge
  4. fuzzy candidate matching
- [x] tracking work ingestion now carries `contentType`, so MALSync can correctly
      choose `anime` vs `manga` lookup kind
- [x] binding lookup was normalized to accept both numeric service ids and lowercase
      service-name keys, reducing duplicate entity splits caused by key-shape mismatch

#### 8.4 Local Source Auto-Match Prompt

When a user opens a local-source work in `DetailsScreen`, the app should opportunistically
search tracking sites in background and surface only high-confidence candidates.

- candidate discovery runs only for local-source details
- existing tracking bindings suppress the prompt
- only a clearly dominant, high-confidence candidate should be surfaced directly
- the prompt must allow the user to:
  - bind to the suggested tracking work
  - inspect the candidate
  - mark it as "not the same work"
- a rejected candidate must be persisted to avoid repeated prompts for the same local
  work and tracking target
- if the user is signed in, binding can additionally trigger online progress linking;
  otherwise local binding still succeeds

**Current implementation status**

- [x] local details page now runs background tracking-candidate discovery
- [x] only a clearly dominant high-confidence candidate is surfaced directly
- [x] user can bind, inspect, or permanently ignore that candidate
- [x] ignored candidate pairs are persisted to avoid repeated prompt spam

#### 8.5 Supporting UI/UX Fixes Landed During Unification

These are not the architectural center of the entity graph work, but they were fixed
while wiring the new cross-source detail surface and top-bar filters.

- [x] top search bar language-preset dropdown now includes a direct entry to manage
      presets, instead of exposing only the `All` option plus existing presets
- [x] suggestions/recommendations page content-type and source-tag filters now
      actually recompute the list against the shared route-level filter state
- [x] generic list-route source-tag callback now correctly supports clearing the
      source-tag filter instead of only toggling non-null tags

#### 8.6 Pending Follow-Up

- migrate history/favorites/updates/subscriptions to entity-aggregated queries
- add multi-candidate review flow when several tracking candidates are close in score
- unify duplicate local-source records once they resolve to the same aggregate entity
- add `.zst` support for Anime Offline dataset downloads to reduce first-download cost
- expose richer Anime Offline diagnostics/version detail if manual refresh failures
  need to be user-visible beyond notifications

## File Inventory

### New Files Created

| File | Purpose |
|------|---------|
| `details/ui/model/DetailsOrigin.kt` | Sealed interface for navigation origin |
| `details/ui/model/ActiveLocalSourceOption.kt` | UI model for active local source switching |
| `details/ui/model/EntityChapterSourceInfo.kt` | UI model for entity detail chapter source status |
| `entitygraph/ui/details/EntityRelationModels.kt` | Shared data classes for relation UI |
| `discover/ui/details/LocalSearchState.kt` | Search state model extracted from deleted VM |
| `tracking/animeoffline/data/AnimeOfflineRepository.kt` | Offline anime cross-site bridge repository |
| `tracking/animeoffline/work/AnimeOfflineUpdateWorker.kt` | Background updater for Anime Offline dataset |

### Files Modified

| File | Changes |
|------|---------|
| `core/nav/ContentIntent.kt` | Added `ContentIntent.of(mangaId)` helper for source switching reloads |
| `core/nav/AppRouter.kt` | Unified routing, removed legacy imports |
| `details/ui/DetailsActivity.kt` | Parses `DetailsOrigin` from intent |
| `details/ui/DetailsViewModel.kt` | Multi-origin init, entity/tracking resolution, debounced relation section publishing, active load intent override, local source switching, chapter source status |
| `entitygraph/data/EntityGraphRepository.kt` | Anime Offline + MALSync-backed entity resolution chain |
| `entitygraph/domain/EntityGraphModels.kt` | Tracking work ingestion now carries `contentType` |
| `details/ui/compose/DetailsScreen.kt` | EntityRelationCarousels render + entity click routing + cover thumbnails |
| `details/ui/compose/DetailsHeader.kt` | Active Local Source switcher chips and chapter source status in details header |
| `tracking/discovery/data/DefaultTrackingSiteMatcher.kt` | Video local-source candidate discovery now prefers Anime Offline bridge |
| `settings/ServicesSettingsFragment.kt` | Anime Offline status summary + manual refresh action |
| `settings/compose/ServicesSettingsScreen.kt` | Anime Offline settings entry in Services page |
| `main/ui/compose/KototoroTopBar.kt` | Language-preset dropdown now links to preset management |
| `suggestions/ui/SuggestionsViewModel.kt` | Suggestions page now listens to the correct shared filter state |
| `tracker/work/TrackerNotificationHelper.kt` | Uses `detailsIntent(Content)` overload |
| `app/src/main/AndroidManifest.xml` | Removed 2 stale legacy Activity entries |

### Files Deleted

| File | Reason |
|------|--------|
| `discover/ui/details/TrackingSiteDetailsActivity.kt` | Replaced by unified DetailsActivity |
| `discover/ui/details/TrackingSiteDetailsViewModel.kt` | Merged into DetailsViewModel |
| `discover/ui/details/TrackingSiteDetailsFragment.kt` | No longer needed |
| `entitygraph/ui/details/EntityDetailsActivity.kt` | Replaced by unified DetailsActivity |
| `entitygraph/ui/details/EntityDetailsViewModel.kt` | Merged into DetailsViewModel |
| `entitygraph/ui/details/PeripheralEntityScreen.kt` | Replaced by EntityRelationCarousels |

## Known Issues

1. **Entity cover art**: Some entities still have no usable binding cover, so
   `EntityRelationCard` keeps a type-icon fallback for incomplete graphs.
2. **Tracking-to-entity resolution**: The `serviceId` field in `DetailsOrigin.TrackingItem`
   is a `String`, but `ScrobblerService.id` is an `Int`. Conversion uses `.toIntOrNull()`.

## Verification

- `./gradlew :app:compileDebugKotlin --no-daemon` ✅
- `rg` audit over `app/src/main/kotlin` and `app/src/main/AndroidManifest.xml` confirms
  no remaining runtime references to `TrackingSiteDetailsActivity`,
  `TrackingSiteDetailsViewModel`, `TrackingSiteDetailsFragment`,
  `TrackingSiteDetailsScreen`, `EntityDetailsActivity`, `EntityDetailsViewModel`,
  `PeripheralEntityScreen`, `UnifiedEntityScreen`, or `TrackingLocalSourcesPanel`
- Entity relation cards now render inside `DetailsScreen`, open nested entity details,
  and resolve thumbnails from local manga / tracking cache bindings when available
- Entity relation carousels now use glass section containers, richer cover-first cards,
  and section/icon badges aligned with the existing `DetailsScreen` visual language
- When an entity has multiple local manga bindings, the details header now exposes
  an Active Local Source switcher and reloads the selected local source in-place
- Entity detail headers now clarify chapter provenance: either the current local source
  used for chapters or an explicit unavailable hint when no local source is active
- Entity relation sections are now published through a debounced flow to reduce
  transient UI churn when relation data settles
- Tracking work ingestion now uses Anime Offline first and MALSync second for
  cross-site entity convergence before falling back to fuzzy bind heuristics
- Anime Offline dataset status and manual force-refresh are now exposed in Services
  settings, while real download progress remains visible through foreground
  notifications
- Top-bar language preset menu now offers a direct path to preset management
- Suggestions page filters now correctly affect list recomputation instead of only
  updating the visible filter state
