# Kototoro – Mihon Extension Compatibility Report

## 1. Goal
Kototoro ships Mihon (ex-Tachiyomi) extension support:
- Detect installed Mihon extension APKs and load their `Source`/`CatalogueSource`.
- Reuse Kototoro networking, cache, and UI while keeping isolation and stability.
- Map Mihon data/filter models into Kototoro’s parser layer seamlessly.

## 2. Architecture (diagram)
```
[Mihon Extension APK]
      | (Manifest: tachiyomi.extension.*)
      v
[MihonExtensionLoader] -- ChildFirstPathClassLoader --> [Source instances]
      |                       ^
      |                       |
      |              [KotoInjektBridge + KotoNetworkHelper]
      v
[MihonExtensionManager] -- caches --> [MihonMangaSource wrappers]
      |
      v
[MihonMangaRepository] -- mapping --> [Kototoro Parser layer] --> [UI: Mihon sources, reader]
```

## 3. Flow Breakdown
### 3.1 Discovery and metadata
- File: `mihon/MihonExtensionLoader.kt`
- Scan installed packages + local APK archives via `ExternalExtensionLoaderSupport` and `LocalApkExtensionSupport`; detect by:
  - Feature `tachiyomi.extension`
  - Package hints via `ExternalExtensionLoaderSupport.looksLikeMihonPackage()`
  - Manifest meta-data: `tachiyomi.extension.class` or `tachiyomi.extension.factory`
- Version gate: `LIB_VERSION_MIN=1.2`, `LIB_VERSION_MAX=1.9`; out-of-range is rejected.
- NSFW flag: `tachiyomi.extension.nsfw`.
- Language: parsed from package name segment after `extension.`.
- TachiyomiX support: `tachiyomix.name` metadata for display name override.

### 3.2 Injekt bridge
- Files: `MihonModule.kt`, `compat/KotoInjektBridge.kt`
- Before loading, call `initialize()` to inject:
  - `Application`/`Context`
  - `OkHttpClient`, `CookieJar`
  - `NetworkHelper` impl: `KotoNetworkHelper` (drops `GZipInterceptor` to avoid bad `Content-Encoding:gzip`)
  - `Json`/`StringFormat`/`SerialFormat`

### 3.3 ClassLoader isolation
- File: `util/ChildFirstPathClassLoader.kt`
- Extends `DexClassLoader` with child-first loading of extension dex to avoid dependency clashes.
- Whitelisted prefixes always delegate to parent: `java.`, `javax.`, `kotlin.`, `kotlinx.`, `android.`, `androidx.`, `org.json.`, `org.jsoup.`, `okhttp3.`, `okio.`, `rx.`, `eu.kanade.tachiyomi.source.`, `eu.kanade.tachiyomi.network.`, `eu.kanade.tachiyomi.util.`, `uy.kohesive.injekt.`, `ireader.core.`, `io.ktor.`, `com.fleeksoft.`.

### 3.4 Source loading and caching
- `loadSources(...)` instantiates `Source` or `SourceFactory#createSources()` via `ExternalExtensionSourceLoaderSupport`.
- `MihonExtensionManager` delegates to `ExternalExtensionManagerFacade` (generic extension lifecycle: load, cache, track success/failure, expose `StateFlow`).
- Wraps `CatalogueSource` into `MihonMangaSource`, appends language suffix when names collide, and tracks NSFW flag.

### 3.5 Model conversion
- File: `model/MihonDataConverters.kt`
- Conversions:
  - `SManga` ↔ `Content`: absolute URLs, cover fallbacks, adult rating, author/state mapping, rating calculation from score.
  - `SChapter` ↔ `ContentChapter`: stable IDs, reverse-list fallback numbering to fix order, volume/scanlator extraction.
  - `Page` ↔ `ContentPage`: unique page IDs from chapter URL + index to avoid cache collisions.
  - Public URLs: safe wrappers around `HttpSource.getMangaUrl/getChapterUrl`.
- URL cleanup handles duplicated base URLs and malformed protocols (`https//`).
- Genre cleaning strips data-class representations (e.g. `ThemeInfo(name=爱情,...)`) from Mihon genre strings.

### 3.6 Filter mapping
- File: `MihonFilterMapper.kt`
- Map Mihon `FilterList` to Kototoro `MangaListFilterOptions` (Header/Group/Select/Sort/Text).
- Reverse mapping applies selected Kototoro tags back to Mihon filters (TriState/include-exclude, Sort).

### 3.7 Repository adaptation
- File: `MihonMangaRepository.kt`
- Lists: map `SortOrder` to Mihon popular/latest/search with aligned pagination.
- Details/chapters: retry on IO, fill missing fields, reverse + renumber chapters to enforce ascending order.
- Images:
  - Copy headers from Mihon `HttpSource`; add Referer if missing.
  - For page URLs needing resolution, use `mihon://resolve` then call `getImageUrl`.
  - Cover requests prefer `imageRequest`, fallback to base implementation.

### 3.8 UI integration
- Mihon sources are managed through the unified source management screen alongside other source types.
- Use case: `GetMihonSourcesUseCase.kt` produces `MihonSourceItem` with language suffix and NSFW flag.
- Source lifecycle: `ExternalExtensionManagerFacade` exposes `StateFlow` for installed/failed extensions, loading state, and change counter.

## 4. Key files
- Loader/Manager: `MihonExtensionLoader.kt`, `MihonExtensionManager.kt`
- Bridge: `MihonModule.kt`, `compat/KotoInjektBridge.kt`, `compat/KotoNetworkHelper.kt`
- Request context: `compat/MihonRequestContext.kt`
- Isolation: `util/ChildFirstPathClassLoader.kt`
- Models: `model/MihonDataConverters.kt`, `model/MihonMangaSource.kt`, `model/MihonLoadResult.kt`
- Filters: `MihonFilterMapper.kt`
- Repository: `MihonMangaRepository.kt`
- UI/Use case: `GetMihonSourcesUseCase.kt`
- Extension lifecycle: `extensions/runtime/ExternalExtensionManagerFacade.kt`
- Extension support: `extensions/runtime/ExternalExtensionLoaderSupport.kt`, `extensions/runtime/ExternalExtensionMetadataSupport.kt`, `extensions/runtime/LocalApkExtensionSupport.kt`

## 5. Compatibility risks
- Version window 1.2–1.9 only.
- Dependency clashes: adjust parent-package whitelist if class conflicts arise.
- Network interceptors: Mihon client omits `GZipInterceptor`; new host interceptors must be copied/filtered in `KotoNetworkHelper`.
- Chapter ordering: some sources may still misorder; add per-source sorting if needed.
- Trust: signature verification is not enforced (`Untrusted` not used); keep extension channels controlled.

## 6. Debug tips
- Scan logs: look for `MihonExtensionLoader` in logcat for feature/name/meta detection.
- Version errors: “Incompatible lib version” → upgrade/downgrade extension.
- Network: `MihonNetwork` logs request/response code and 200-char preview on failures.
- URL issues: heed `MihonDataConverters` warnings (duplicate baseUrl, bad protocol); patch per-source if necessary.
- Filters: confirm `MihonFilterMapper` applies TriState/Sort choices correctly.
