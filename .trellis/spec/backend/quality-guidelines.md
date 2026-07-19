# Quality Guidelines

> Code quality standards for backend development.

---

## Overview

<!--
Document your project's quality standards here.

Questions to answer:
- What patterns are forbidden?
- What linting rules do you enforce?
- What are your testing requirements?
- What code review standards apply?
-->

(To be filled by the team)

---

## Forbidden Patterns

<!-- Patterns that should never be used and why -->

(To be filled by the team)

---

## Required Patterns

<!-- Patterns that must always be used -->

(To be filled by the team)

---

## Testing Requirements

<!-- What level of testing is expected -->

(To be filled by the team)

---

## Code Review Checklist

<!-- What reviewers should check -->

(To be filled by the team)

---

## Scenario: Mihon Default Network Client Compatibility

### 1. Scope / Trigger

- Applies when implementing or changing `eu.kanade.tachiyomi.network.NetworkHelper` or the OkHttp client exposed to Mihon extensions.
- Some extensions inspect interceptor runtime types, so functionally equivalent anonymous interceptors are not compatible.

### 2. Signatures

The default client must begin with these concrete host classes in this order:

```kotlin
eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
```

Construction in `KotoNetworkHelper`:

```kotlin
builder.addInterceptor(UncaughtExceptionInterceptor())
builder.addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
builder.addInterceptor(CloudflareInterceptor())
```

### 3. Contracts

- `UncaughtExceptionInterceptor` is first and converts non-`IOException` failures from later interceptors into `IOException`.
- `UserAgentInterceptor` is second, preserves a non-empty request User-Agent, and otherwise uses `defaultUserAgentProvider`.
- `CloudflareInterceptor` is third and provides the concrete Mihon runtime type. Kototoro's existing downstream Cloudflare detector remains responsible for its host-specific challenge flow.
- Additional Kototoro interceptors may follow these three entries.
- `BrotliInterceptor` must be filtered from both `interceptors` and `networkInterceptors` when copying the Kototoro base client. Mihon extensions manage Brotli compatibility themselves and may reject a default client that installs it.
- Keep a separate legacy compatibility client exposed through the existing
  `cloudflareClient` property with `BrotliInterceptor` in its network chain.
  Legacy `HttpSource` clients use this path; `KeiSource` must continue using
  the Brotli-free `NetworkHelper.client` path.
- The host runtime must package `okhttp3.zstd.Zstd` through the OkHttp-version-matched `okhttp-zstd` artifact. Dynamic extension references are not visible to the app compiler, so keep a host-side reference and retain `okhttp3.**` in release rules.

### 4. Validation & Error Matrix

| Condition | Expected result |
| --- | --- |
| First concrete type is missing | Extension may throw `UncaughtExceptionInterceptor must be present in default client` |
| Second concrete type is missing | Extension may throw `UserAgentInterceptor must be present in default client` |
| Third concrete type is missing | Extension may throw `CloudflareInterceptor must be present in default client` |
| Brotli exists in either interceptor list | Extension may throw `BrotliInterceptor must not be present in default client` |
| `okhttp3.zstd.Zstd` is absent from the APK | Extension fails with `NoClassDefFoundError` before creating its client |
| Anonymous interceptor has equivalent behavior | Still invalid because runtime type checks fail |
| Request already has a non-empty User-Agent | Preserve the source-provided value |

### 5. Good / Base / Bad Cases

- Good: the three concrete compatibility interceptors are entries 0, 1, and 2, followed only by compatible Kototoro interceptors; Brotli is absent from both lists.
- Base: a source without runtime validation continues through the same chain and retains existing network behavior.
- Bad: replacing any required concrete class with a lambda, using a same-named class in another package, or blindly copying every base-client network interceptor.

### 6. Tests Required

- Construct `KotoNetworkHelper` with a minimal `OkHttpClient`.
- Assert `client.interceptors[0..2]` have the exact Java classes listed above.
- Build the base client with Brotli in both interceptor lists and assert the resulting Mihon client contains neither instance.
- Assert `Zstd.encoding == "zstd"` and verify the Android runtime resolves `zstd-kmp-android`.
- Compile the compatibility test whenever any Mihon network bridge or interceptor changes.

### 7. Wrong vs Correct

Wrong:

```kotlin
builder.addInterceptor { chain -> chain.proceed(chain.request()) }
```

Correct:

```kotlin
builder.addInterceptor(UncaughtExceptionInterceptor())
builder.addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
builder.addInterceptor(CloudflareInterceptor())
baseClient.networkInterceptors
    .filterNot { it === BrotliInterceptor }
    .forEach(builder::addNetworkInterceptor)
```

---

## Scenario: Mihon Legacy and Suspend API Bridging

### 1. Scope / Trigger

- Applies to `HttpSource` methods that expose both legacy Rx `fetch*` APIs and suspend `get*` APIs.
- Extensions may override a legacy method and call `super.fetch*().map(...)`; the base implementation must never route back into the suspend method.

### 2. Signatures

The rule applies to these pairs:

```kotlin
fetchPopularManga / getPopularManga
fetchSearchManga / getSearchManga
fetchLatestUpdates / getLatestUpdates
fetchMangaDetails / getMangaDetails
fetchChapterList / getChapterList
fetchPageList / getPageList
fetchImageUrl / getImageUrl
```

### 3. Contracts

- Direction is one-way: suspend `get*` may delegate to an extension-overridden legacy `fetch*`.
- Base legacy `fetch*` executes its request and parser directly through `legacyFetch`; it must not call `runBlocking { get*() }`.
- Direct legacy execution preserves `MihonRequestContext`, `ContentSource` request tags, response closing, and unsuccessful HTTP status conversion to `HttpException`.
- Search parsing uses the same redirect-to-details fallback in both API paths.

### 4. Validation & Error Matrix

| Condition | Expected result |
| --- | --- |
| Extension overrides `fetchMangaDetails` and calls `super` | One request, mapped result, no recursion |
| Base `fetch*` calls matching `get*` | Invalid; may alternate through Rx and `runBlocking` until `StackOverflowError` |
| HTTP response is unsuccessful | Close response and emit `HttpException` |
| Search response redirects to a detail page | Reuse the shared detail fallback parser |

### 5. Good / Base / Bad Cases

- Good: `getMangaDetails -> extension fetchMangaDetails -> super.fetchMangaDetails -> request/parse`.
- Base: an extension using only request/parse helpers is served directly by the suspend path.
- Bad: `fetchMangaDetails -> runBlocking(getMangaDetails) -> fetchMangaDetails`.

### 6. Tests Required

- Add a source overriding `fetchMangaDetails`, delegating to `super`, and mapping the result.
- Assert the returned mapping and exactly one MockWebServer request path.
- Keep equivalent chapter and custom page-fetch tests in the compatibility suite.

### 7. Wrong vs Correct

Wrong:

```kotlin
override fun fetchMangaDetails(manga: SManga) = Observable.fromCallable {
    runBlocking { getMangaDetails(manga) }
}
```

Correct:

```kotlin
override fun fetchMangaDetails(manga: SManga) = legacyFetch(
    request = { mangaDetailsRequest(manga) },
    parser = { mangaDetailsParse(it).apply { initialized = true } },
)
```

---

## Scenario: Mihon Repository Combined Detail Updates

### 1. Scope / Trigger

- Applies when a repository loads both manga details and chapters from a Mihon source.
- TachiyomiX 1.6 sources may implement only the combined update API and intentionally throw `UnsupportedOperationException` from legacy request helpers.

### 2. Signatures

Use the source-level combined API as the repository boundary:

```kotlin
suspend fun getMangaUpdate(
    manga: SManga,
    chapters: List<SChapter>,
    fetchDetails: Boolean,
    fetchChapters: Boolean,
): SMangaUpdate
```

For a full details load, pass the current content's chapter snapshots when
available, otherwise an empty list; use `fetchDetails = true` and
`fetchChapters = true`.

### 3. Contracts

- `MihonMangaRepository.getDetailsImpl` makes one logical combined update call and consumes both `SMangaUpdate.manga` and `SMangaUpdate.chapters`.
- Existing `Content.chapters` are converted back to `SChapter` snapshots for
  the combined call, so sources that use the supplied chapter state receive
  the same context as the UI currently holds.
- Do not split a full details load into repository calls to `getMangaDetails` and `getChapterList`.
- Legacy sources remain supported by the default `Source.getMangaUpdate` implementation, which delegates to the separate legacy-compatible methods.
- Sources overriding `getMangaUpdate` may leave `mangaDetailsRequest` and `chapterListRequest` unsupported.
- `SManga.memo` and `SChapter.memo` are extension request context, not disposable presentation metadata. Preserve the source objects across `SManga -> Content -> SManga` and `SChapter -> ContentChapter -> SChapter` round trips.
- `MihonMangaRepository` keeps defensive copies in bounded per-repository LRU caches: 100 manga snapshots and 500 chapter snapshots, keyed by the source-provided URL. Never pass the cached mutable instance directly to an extension.
- A details update refreshes the manga snapshot. Converting returned chapters stores each original chapter snapshot before producing `ContentChapter` values.
- The combined call remains inside `MihonRequestContext` and the existing exception unwrapping boundary.
- On an `IOException` or an exception directly caused by one, retry the entire combined update once after 500 ms. Do not retry details and chapters independently.

### 4. Validation & Error Matrix

| Condition | Expected result |
| --- | --- |
| Source overrides `getMangaUpdate` | Use its returned manga and chapters without invoking legacy request helpers |
| Source relies on default `getMangaUpdate` | Bridge to its detail and chapter APIs through the default implementation |
| Legacy request helper throws `UnsupportedOperationException` | Combined-only source still succeeds; do not catch UOE as a network retry |
| Combined update reads a missing `SManga.memo` entry | Invalid round trip; extensions may fail with an internal NPE before making a request |
| Page list reads a missing `SChapter.memo` entry | Invalid round trip; extensions may throw errors such as `Refresh Chapter List` |
| Snapshot exists | Pass a defensive copy with all 1.6 fields, including `memo` |
| Snapshot is absent or evicted | Fall back to the existing generic model conversion; do not use an unbounded cache |
| First combined call fails with `IOException` | Delay 500 ms and retry the whole call once |
| Failure is not caused by `IOException` | Propagate it without retry |

### 5. Good / Base / Bad Cases

- Good: a combined-only source receives its list-item `memo`, returns chapters with `memo`, and later receives that chapter metadata in `getPageList`.
- Base: an older source inherits `getMangaUpdate` and receives the same details-plus-chapters result through its existing APIs.
- Bad: rebuilding only generic fields discards `memo`; the details screen may open but reading fails when the extension needs a chapter token or manga ID.

### 6. Tests Required

- Define a source overriding `getMangaUpdate` whose legacy detail and chapter request helpers throw UOE; assert both combined result fields.
- Keep a legacy source test proving the default combined implementation returns updated details and chapters.
- Add a repository round-trip test: list manga contains `memo.slug`, combined details must receive it, returned chapter contains `memo.mangaId`, and page loading must receive it.
- Make the test fail when either memo key is absent; asserting an extension method directly is insufficient because it does not cover model conversion.
- When changing repository retry behavior, assert one retry for IO failures and no retry for UOE or other failures.

### 7. Wrong vs Correct

Wrong:

```kotlin
val details = source.getMangaDetails(manga)
val chapters = source.getChapterList(manga)
```

Correct:

```kotlin
val update = source.getMangaUpdate(
    manga = manga,
    chapters = emptyList(),
    fetchDetails = true,
    fetchChapters = true,
)
val details = update.manga
val chapters = update.chapters
```

For model round trips, preserve extension snapshots rather than reconstructing only shared fields:

```kotlin
// Wrong: drops memo and other extension-specific fields.
val sourceChapter = contentChapter.toMihonChapter()

// Correct: use a defensive snapshot when available, with generic conversion as fallback.
val sourceChapter = chapterSnapshots[contentChapter.url]?.snapshot()
    ?: contentChapter.toMihonChapter()
```

## Scenario: Entity Graph Projection Details Resolution

### 1. Scope / Trigger

Applies when a details route is opened from History, Favourites, or another
Entity Graph entry and the route carries a concrete local projection ID. It
also applies when a cached Mihon projection must recover its source, locale,
or content type before the enabled-source registry has finished loading.

### 2. Signatures

```kotlin
data class DetailsOrigin.EntityGraph(
    val entityId: Long,
    val preferredLocalMangaId: Long? = null,
    val initialProjectionLocalMangaId: Long? = null,
)

data class MangaEntity(
    // Persisted projection identity and type fallback.
    val source: String,
    val contentType: String? = null,
)

private fun ContentSource.resolveDetailsSource(): ContentSource
```

The history/navigation boundary must pass the originating projection's local
`manga.id` as `initialProjectionLocalMangaId`; the Work/entity ID is not a
substitute.

### 3. Contracts

- Resolve and load the explicit initial projection before constructing a
  synthetic Entity Graph header. When available, the cached projection is the
  first details state and remains the provider/chapter/related-content seed.
- Synthetic `Entity Graph` content is presentation-only. It must never be
  passed to `ContentRepositoryFactory`, chapter mapping, related-content
  loading, or a Mihon repository when a real local projection exists.
- Resolve a cached `MIHON_*` source in this order: enabled source registry,
  installed Mihon source registry (including installed-but-disabled sources),
  then the generic `ContentSource` fallback. The resolved source supplies the
  display locale and source content type.
- Use persisted `MangaEntity.contentType` as the projection type fallback
  when the anonymous cached source cannot infer its real type. Keep the
  content-type/Space compatibility filter from issue 409; source resolution
  fixes its inputs and must not remove the filter.
- Re-run source options and presentation resolution when the Mihon extension
  manager changes, so a source installed after the first render can restore
  language and reading-source labels without pull-to-refresh.
- Do not infer a language from the Work/entity header or treat an empty locale
  as a valid language.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| Entity Graph has a valid initial projection | Load that local projection first and preserve its ID |
| Initial projection is absent or missing | Keep the entity route diagnosable; do not use the entity ID as a local manga ID |
| Cached projection exists before enabled sources load | Show its metadata/reading source from the cached projection where possible |
| Mihon source is installed but disabled | Resolve its locale and content type from `MihonExtensionManager` |
| Cached source type differs from real Mihon source type | Prefer persisted projection type and real-source resolution; retain issue 409 filtering |
| Synthetic `Entity Graph` source reaches a provider request | Invalid; provider lookup may return `EmptyContentRepository` |
| Source registry changes after first render | Recompute source options and language without requiring manual refresh |
| Source locale is blank | Display unknown language; never display a guessed locale |

### 5. Good / Base / Bad Cases

- Good: History passes the AllManga projection ID, the cached projection is
  displayed immediately, and the installed source later supplies `en` for
  both metadata and reading-source presentation.
- Base: An enabled ordinary Mihon source resolves from the active registry and
  behaves as before.
- Bad: Seed the ViewModel with the Work ID or synthetic `Entity Graph` source,
  then query providers before applying the cached projection; this hides the
  real reading source and can trigger unsupported-source failures.

### 6. Tests Required

- Test that an Entity Graph origin preserves `initialProjectionLocalMangaId`
  through ViewModel initialization and projection selection.
- Test that a cached real projection is preferred over a synthetic entity
  header and is not replaced by an `Entity Graph` provider seed.
- Test that persisted `contentType` restores projection filtering when the
  anonymous cached Mihon source has a different inferred type.
- Test source resolution for an installed-but-disabled Mihon source and
  assert that its locale is exposed in metadata/reading-source state.
- Test that issue 409 content-type filtering still rejects an incompatible
  projection while accepting a projection after source/type reconciliation.

### 7. Wrong vs Correct

Wrong:

```kotlin
val origin = DetailsOrigin.EntityGraph(entityId = entity.id)
val source = ContentSource(cachedManga.source)
val provider = contentRepositoryFactory.create(Content(entityId, source))
```

Correct:

```kotlin
val origin = DetailsOrigin.EntityGraph(
    entityId = entity.id,
    initialProjectionLocalMangaId = historyContent.id,
)
val cachedProjection = dataRepository.findContentById(
    origin.initialProjectionLocalMangaId,
    withChapters = true,
)
val source = cachedProjection.source.resolveDetailsSource()
// Use cachedProjection for provider/chapter flows; synthetic entity content
// remains a presentation fallback only.
```
