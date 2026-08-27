# AndroidX Paging 3 — Research for the Favourites / Library Grid Screen

**Purpose:** Citable Paging 3 guidance (Paging 3.4.x/3.5.0, Compose) for a favourites/library grid of hundreds-to-thousands of manga cover cards.

**Method:** every URL was fetched directly and returned HTTP 200 on 2026-09-01 (check-list at end); quotes are verbatim. Primary sources only: developer.android.com Paging guides/references, `androidx/androidx` source, Paging release notes, developer.android.com KMP docs.

> **URL note:** several listed URLs now 404: `v3-paging-data`→`v3-paged-data`, `v3-paged-data-transformation`→`v3-transform`, `v3-load-state`→`load-state`; `v3-paging-source`, `v3-key-management`, `/overview` no longer exist (content now in `v3-paged-data` + `PagingSource`/`PagingConfig` refs).

## 1. What PagingData represents / why paging for large lists

| Claim | Source URL | Short literal quote | Section/anchor | Accessed |
|---|---|---|---|---|
| Paging loads/displays only pages of a larger dataset, so you never hold the whole library (thousands of covers) in memory at once; it exists precisely for large lists. | https://developer.android.com/topic/libraries/architecture/paging/v3-overview | "load and display pages of data from a larger dataset from local storage or over a network. This approach lets your app use both network bandwidth and system resources more efficiently." | Overview (intro) | 2026-09-01 |
| PagingData is an immutable **snapshot**: it grows as pages load but its data can't be updated; any data change (reorder/insert/delete) needs a new PagingSource/PagingData generation. | https://github.com/androidx/androidx/blob/androidx-main/paging/paging-common/src/commonMain/kotlin/androidx/paging/PagingSource.kt | "A `PagingData` can grow as it loads more data, but the data loaded cannot be updated." | Class KDoc — "Updating Data" | 2026-09-01 |
| Incremental loading is how Paging cuts initial load time and memory — the reason to use it for a large image/OCR-heavy grid. | https://developer.android.com/develop/ui/compose/quick-guides/content/lazily-load-list | "support large lists of items … by loading and displaying data incrementally. This technique enables you to reduce initial load times and optimize memory usage" | Intro | 2026-09-01 |

## 2. PagingConfig parameters, placeholders, and their cost

| Claim | Source URL | Short literal quote | Section/anchor | Accessed |
|---|---|---|---|---|
| `pageSize` = items loaded at once and "several times the number of visible items onscreen"; for a tiled grid the docs point toward ~100. | https://developer.android.com/reference/kotlin/androidx/paging/PagingConfig | "Should be several times the number of visible items onscreen." / "If you're displaying dozens of items in a tiled grid … consider closer to 100." | `pageSize` | 2026-09-01 |
| Defaults: `prefetchDistance = pageSize`, `enablePlaceholders = true`, `initialLoadSize = pageSize * DEFAULT_INITIAL_PAGE_MULTIPLIER`, `maxSize = MAX_SIZE_UNBOUNDED`. | https://developer.android.com/reference/kotlin/androidx/paging/PagingConfig | "prefetchDistance: … = pageSize, enablePlaceholders: Boolean = true, initialLoadSize: … = pageSize * DEFAULT_INITIAL_PAGE_MULTIPLIER, maxSize: … = MAX_SIZE_UNBOUNDED" | Constructor | 2026-09-01 |
| Null placeholders appear only if **both**: the source can count all unloaded items, and `enablePlaceholders` is true. | https://developer.android.com/reference/kotlin/androidx/paging/PagingConfig | "`PagingData` will present `null` placeholders … if two conditions are met: 1) Its `PagingSource` can count all unloaded items … 2) `enablePlaceholders` is set to `true`" | `enablePlaceholders` | 2026-09-01 |
| Custom sources with placeholders must also return `nextKey`/`prevKey` in `LoadResult.Page`, and the UI must render `null` as a placeholder. | https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data | "Custom paging sources must also implement `nextKey` and `prevKey` in the returned `LoadResult.Page`." | `#pagingsource` / `#collect-and-display` | 2026-09-01 |
| `getRefreshKey` is mandatory so the next generation resumes around the user's scroll anchor after refresh/invalidation. | https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data + https://developer.android.com/topic/libraries/architecture/paging/v3-migration | "The `PagingSource` implementation must also implement a `getRefreshKey` method" | `#pagingsource`, `#refresh-keys` | 2026-09-01 |
| Paging must always be able to trigger more loading — placeholders or nonzero prefetch distance are required. | https://github.com/androidx/androidx/blob/androidx-main/paging/paging-common/src/commonMain/kotlin/androidx/paging/PagingConfig.kt | "Placeholders and prefetch are the only ways to trigger loading of more data in PagingData, so either placeholders must be enabled, or prefetch distance must be > 0." | `PagingConfig` init | 2026-09-01 |
| Tradeoff: header/footer load-state items only behave as expected with placeholders **disabled**; otherwise they appear after the placeholder region. | https://developer.android.com/topic/libraries/architecture/paging/load-state | "The following example only works if placeholders are disabled. Otherwise, the load state items will be displayed at the end of placeholders." | `#header-footer` | 2026-09-01 |
| `prefetchDistance = 0` is discouraged: users see a placeholder (with) or an abrupt end of list (without) while scrolling. | https://developer.android.com/reference/kotlin/androidx/paging/PagingConfig | "users don't observe a placeholder item (with placeholders) or end of list (without) while scrolling." | `prefetchDistance` | 2026-09-01 |
| `maxSize` caps in-memory items by dropping pages (best-effort); the default keeps everything. | https://developer.android.com/reference/kotlin/androidx/paging/PagingConfig | "Defines the maximum number of items that may be loaded into `PagingData` before pages should be dropped." / "This can be used to cap the number of items kept in memory by dropping pages." | `maxSize` | 2026-09-01 |

## 3. PagingSource contract

| Claim | Source URL | Short literal quote | Section/anchor | Accessed |
|---|---|---|---|---|
| `load()` (suspend) returns `LoadResult.Page` (success), `Error` (expected/recoverable), or `Invalid` (source no longer valid). | https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data | "If the load is successful, return a `LoadResult.Page` … return a `LoadResult.Error` … or `LoadResult.Invalid`." | `#pagingsource` | 2026-09-01 |
| `LoadResult.Error` becomes `LoadState.Error` in the UI and may be retried. | https://developer.android.com/reference/kotlin/androidx/paging/PagingSource | "This failure will be forwarded to the UI as a `LoadState.Error`, and may be retried." | `LoadResult.Error` KDoc | 2026-09-01 |
| Key chain: `nextKey` drives next Append, `prevKey` drives next Prepend; `null` = no more data in that direction. | https://github.com/androidx/androidx/blob/androidx-main/paging/paging-common/src/commonMain/kotlin/androidx/paging/PagingSource.kt | "`[Prepend]` - `LoadResult.Page.prevKey` … `[Append]` - `LoadResult.Page.nextKey` of the loaded page at the end of the list." | `LoadParams.key` KDoc | 2026-09-01 |
| `LoadResult.Invalid` is the documented "dataset changed underneath" signal (e.g. DB writes): Paging discards pending loads and invalidates the source. | https://github.com/androidx/androidx/blob/androidx-main/paging/paging-common/src/commonMain/kotlin/androidx/paging/PagingSource.kt | "return `LoadResult.Invalid`, which causes Paging to discard any pending or future load requests to this `PagingSource` and invalidate it." | `LoadResult.Invalid` KDoc | 2026-09-01 |
| `invalidate()` stops the source (idempotent); sources must detect underlying changes and call it. | https://github.com/androidx/androidx/blob/androidx-main/paging/paging-common/src/commonMain/kotlin/androidx/paging/PagingSource.kt | "signal the `PagingSource` to stop loading. This method is idempotent." / "[A `PagingSource`] must detect that it cannot continue loading its snapshot … and call `invalidate`." | `invalidate()` / class KDoc | 2026-09-01 |
| `Pager` builds generations from `pagingSourceFactory` (a fresh source per generation) + a `PagingConfig`. | https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data | "a `PagingConfig` configuration object and a function that tells `Pager` how to get an instance of your `PagingSource` implementation" | `#pagingdata-stream` | 2026-09-01 |

## 4. cachedIn() — reuse, sharing, cache lifetime

| Claim | Source URL | Short literal quote | Section/anchor | Accessed |
|---|---|---|---|---|
| `cachedIn(scope)` makes the PagingData stream shareable/recollectable and caches loaded data inside the provided `CoroutineScope`; without it the PagingData can't be recollected. | https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data | "The `cachedIn` operator … caches the loaded data with the provided `CoroutineScope`. Without `cachedIn`, the `PagingData` cannot be recollected on." | `#pagingdata-stream` | 2026-09-01 |
| Apply it in the ViewModel with `viewModelScope`; it caches results of transformations placed before it. | https://developer.android.com/topic/libraries/architecture/paging/v3-transform | "The `cachedIn()` operation caches the results of any transformations that occur before it. Typically, you apply this operator in your `ViewModel`…" | `#avoid-duplicate` | 2026-09-01 |
| Cache lifetime = that scope's lifetime, so use one that lives as long as the screen (e.g. `viewModelScope`). | https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data + https://developer.android.com/topic/libraries/architecture/paging/v3-transform | "caches the loaded data with the provided `CoroutineScope`" + "caches the results of any transformations that occur before it" | `#pagingdata-stream`, `#avoid-duplicate` | 2026-09-01 |
| Each `PagingData` is single-use by default; `cachedIn` multicasts it so repeated collections work. | https://developer.android.com/topic/libraries/architecture/paging/v3-transform | "By default you can only use each instance of `PagingData` once. … consider using the `cachedIn()` operator, which multicasts the stream." | `#avoid-duplicate` | 2026-09-01 |

## 5. LoadState & CombinedLoadStates

| Claim | Source URL | Short literal quote | Section/anchor | Accessed |
|---|---|---|---|---|
| A separate `LoadState` exists per `LoadType` **and** per source type (PagingSource vs RemoteMediator), all surfaced via `CombinedLoadStates`. | https://developer.android.com/topic/libraries/architecture/paging/load-state | "A separate `LoadState` signal is provided for each `LoadType` and data source type (either `PagingSource` or `RemoteMediator`)." | `#states` | 2026-09-01 |
| LoadState is `NotLoading` (incl. `endOfPaginationReached`), `Loading`, or `Error`. | https://developer.android.com/topic/libraries/architecture/paging/load-state | "`LoadState` objects take one of three forms: `NotLoading` (incl. `endOfPaginationReached`), `Loading`, `Error`." | `#states` | 2026-09-01 |
| In Compose read `loadState.refresh/.append/.prepend`; drive footer/header from `append`/`prepend` and refresh/retry from `refresh`. | https://developer.android.com/topic/libraries/architecture/paging/load-state | "returns a `CombinedLoadStates` object that lets you react to loading behavior for refresh, append, or prepend events." / "monitor the prepend state for the header and the append state for the footer." | `#listener`, `#header-footer` | 2026-09-01 |
| `LoadStateAdapter` (header/footer + retry) is the RecyclerView/Views-side equivalent. | https://developer.android.com/topic/libraries/architecture/views/paging/load-state-views | "create a class that implements `LoadStateAdapter`, and define the `onCreateViewHolder()` … `onBindViewHolder()`" | "Use a LoadStateAdapter" | 2026-09-01 |
| With RemoteMediator, check `loadState.mediator?.refresh` vs `loadState.source.refresh` (e.g. sync spinner only when the local DB is empty). | https://developer.android.com/topic/libraries/architecture/paging/load-state | "it obscures the difference between loading from your local database (`PagingSource`) and your network (`RemoteMediator`)." | `#additional-info` | 2026-09-01 |
| `refresh()` makes a new generation (UI-driven, e.g. pull-to-refresh); `retry()` retries only the current generation's failed loads. | https://developer.android.com/reference/kotlin/androidx/paging/compose/LazyPagingItems | "…should instead use `PagingSource.invalidate`." / "Unlike `refresh`, this does not invalidate `PagingSource`, it only retries failed loads within the same generation of `PagingData`." | `refresh`, `retry` | 2026-09-01 |

## 6. RemoteMediator (network + DB)

| Claim | Source URL | Short literal quote | Section/anchor | Accessed |
|---|---|---|---|---|
| Use it when paging from a network source served into a local DB cache: UI is driven from the DB, network hit only when the DB runs out. | https://developer.android.com/topic/libraries/architecture/paging/v3-network-db | "drives the UI from a local database cache and only makes requests to the network when there is no more data in the database." | Intro | 2026-09-01 |
| With RemoteMediator+Room the DB is the source of truth and pages are written to the DB, making a ViewModel in-memory `cachedIn` cache unnecessary for the data itself. | https://developer.android.com/topic/libraries/architecture/paging/v3-network-db | "`RemoteMediator` stores the new data in the local database, so an in-memory cache in the `ViewModel` is unnecessary." | `#paging-lifecycle` | 2026-09-01 |
| `load()` receives `LoadType` (`REFRESH`/`APPEND`/`PREPEND`) + `PagingState` and returns `MediatorResult` — `Success(endOfPaginationReached=…)` or `Error`. | https://developer.android.com/topic/libraries/architecture/paging/v3-network-db | "`LoadType`, which indicates the type of the load: `REFRESH`, `APPEND`, or `PREPEND`." / "`MediatorResult` can either be `MediatorResult.Error` … or `MediatorResult.Success`" | `#implement-remotemediator` | 2026-09-01 |
| `endOfPaginationReached = true` = no further network pages (empty response / last page). | https://developer.android.com/topic/libraries/architecture/paging/v3-network-db | "return `MediatorResult.Success(endOfPaginationReached = true)`" when "the received list of items is empty or it is the last page index" | `#implement-remotemediator` | 2026-09-01 |
| For a local-only favourites screen with no network layer, no RemoteMediator is needed — a plain (e.g. Room-generated) `PagingSource` is the documented simple path. | https://developer.android.com/topic/libraries/architecture/paging/v3-overview | "`RemoteMediator` … handles paging from a layered data source, such as a network data source with a local database cache." | `#repository` | 2026-09-01 |

## 7. Kotlin Multiplatform

| Claim | Source URL | Short literal quote | Section/anchor | Accessed |
|---|---|---|---|---|
| Paging 3.3.0 became KMP-compatible: `paging-common` API lives in `common` (jvm+iOS), `paging-compose` moved to `common`; runtime/guava/rxjava stay Android-only. | https://developer.android.com/jetpack/androidx/releases/paging | "Paging now ships artifacts compatible with Kotlin Multiplatform … `paging-common` has moved all Paging 3 APIs to `common` … `paging-runtime` … will remain Android only." | `#3.3.0` | 2026-09-01 |
| Paging ≥3.4 extended KMP targets: `paging-common`/`paging-testing`/`paging-compose` support JVM/Desktop, Native (Linux, iOS, …), and Web (JS, Wasm). | https://developer.android.com/jetpack/androidx/releases/paging | "Added more KMP targets … In total they all now support JVM(Android and Desktop), Native (Linux, iOS, watchOS, tvOS, macOS, MinGW), and Web (JavaScript, WasmJS)" | `#3.4.0` | 2026-09-01 |
| Google's KMP page lists Paging among official KMP-ready Jetpack libraries on Android, iOS, JVM, Web (current 3.5.0). | https://developer.android.com/kotlin/multiplatform | "Many of our Jetpack libraries have already been migrated to be KMP-ready." — table row `paging` → Android/iOS/JVM/Web | `#kotlin-multiplatform-and-jetpack-libraries` | 2026-09-01 |
| The Compose integration (`collectAsLazyPagingItems`/`LazyPagingItems`) is marked `Cmn` and ships in `androidx.paging:paging-compose`. | https://developer.android.com/reference/kotlin/androidx/paging/compose/collectAsLazyPagingItems.composable | "Collects values from this `Flow` of `PagingData` and represents them inside a `LazyPagingItems` instance." — `Cmn`, "Artifact: androidx.paging:paging-compose" | Function summary | 2026-09-01 |

## 8. Compose presentation for a grid

| Claim | Source URL | Short literal quote | Section/anchor | Accessed |
|---|---|---|---|---|
| Canonical flow: `collectAsLazyPagingItems()` → `LazyPagingItems` → `items(itemCount)` in the lazy layout; render `null` while placeholder pages load. | https://developer.android.com/develop/ui/compose/lists | "pass in the returned `LazyPagingItems` to `items()` in our `LazyColumn` … display placeholders… by checking if the `item` is `null`." | `#large-datasets` | 2026-09-01 |
| `get(index)` returns the item **and notifies Paging** (drives prefetch); `peek(index)` returns it without triggering loads. | https://developer.android.com/reference/kotlin/androidx/paging/compose/LazyPagingItems | "Returns the presented item at the specified position, notifying Paging of the item access to trigger any loads necessary to fulfill prefetchDistance." / "without notifying Paging … that would normally trigger page loads." | `get`, `peek` | 2026-09-01 |
| `LazyVerticalGrid` (`GridCells.Adaptive(100.dp)`) is documented on the same page; official Paging examples use `LazyColumn`; grid `pageSize` guidance (~100) is in PagingConfig. | https://developer.android.com/develop/ui/compose/lists + https://developer.android.com/reference/kotlin/androidx/paging/PagingConfig | "LazyVerticalGrid( columns = GridCells.Adaptive(100.dp) )" / "If you're displaying dozens of items in a tiled grid … consider closer to 100." | `#lazy-grids`, `pageSize` | 2026-09-01 |
| **Placeholder sizing warning (directly relevant to cover cards):** undersized/empty placeholder rows can keep the screen from filling and trigger many extra network page fetches — size placeholders like real covers. | https://developer.android.com/develop/ui/compose/lists | "If small placeholders are provided (or no placeholder at all), the screen might never be filled …" | `#large-datasets` (Warning) | 2026-09-01 |
| For async image loading (covers), keep item sizing stable before/after load — e.g. placeholders — to preserve scroll position; avoid 0-pixel items. | https://developer.android.com/develop/ui/compose/lists | "ensure your items' sizing remains the same before and after loading, for example, by adding some placeholders. This will help maintain the correct scroll position." | "Avoid using 0-pixel sized items" | 2026-09-01 |

## 9. distinctUntilChanged / redundant emissions

| Claim | Source URL | Short literal quote | Section/anchor | Accessed |
|---|---|---|---|---|
| The only official Paging-doc use of `distinctUntilChanged` is isolating one-shot load-state side effects via `snapshotFlow` — not deduping the PagingData flow itself. | https://developer.android.com/topic/libraries/architecture/paging/load-state | "This lets you apply standard `Flow` operators like `filter` and `distinctUntilChanged` to isolate specific events." | `#chain-operators` | 2026-09-01 |
| No official guidance recommends `distinctUntilChanged` on `Flow<PagingData>`; the documented way to avoid redundant generations is `PagingSource.invalidate()` (emit a new generation only when data changed). | https://github.com/androidx/androidx/blob/androidx-main/paging/paging-common/src/commonMain/kotlin/androidx/paging/PagingSource.kt | "[A `PagingSource`] must detect that it cannot continue loading its snapshot … and call `invalidate`." | Class KDoc | 2026-09-01 |

---

## Gaps / could not verify

1. **"Separating UI loading and data loading"** — not verbatim; closest sourced equivalents: the `enablePlaceholders` conditions + the load-state note (header/footer items sit at the end of placeholders).
2. **`androidx.paging` → `androidx.paging3` rename** — unverifiable: androidx source uses `package androidx.paging` / `androidx.paging.compose`; release notes mention no rename.
3. **Official `LazyPagingItems`-in-`LazyVerticalGrid` example** — none found; docs pair Paging with `LazyColumn`, grid page is separate.
4. **`distinctUntilChanged` for PagingData-flow dedup** — not in official Paging docs (only `snapshotFlow` side-effect use).
5. **`cachedIn` "lifetime = scope lifetime"** — practical implication of the cited quotes; no verbatim sentence found.
6. **Dead URLs (404, 2026-09-01):** `/v3-paging-data`, `/v3-paging-source`, `/v3-paged-data-transformation`, `/v3-load-state`, `/v3-key-management`, `/overview` → replacements used above.

## Verified URLs (all HTTP 200, 2026-09-01)

- developer.android.com: paging/v3-overview · paging/v3-paged-data · paging/v3-transform · paging/load-state · views/paging/load-state-views · paging/v3-network-db · paging/v3-migration · reference/kotlin/androidx/paging/PagingConfig · PagingSource · Pager · PagingData · compose/collectAsLazyPagingItems.composable · compose/LazyPagingItems · develop/ui/compose/lists · develop/ui/compose/quick-guides/content/lazily-load-list · jetpack/androidx/releases/paging · kotlin/multiplatform
- github.com/androidx/androidx (androidx-main): paging/paging-common/src/commonMain/kotlin/androidx/paging/PagingSource.kt · PagingConfig.kt · paging/paging-common/api/current.txt
