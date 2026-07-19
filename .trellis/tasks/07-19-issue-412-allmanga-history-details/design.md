# Technical Design: Legacy Mihon and Keiyoushi Compatibility

## 0. Compatibility target

The host must support two extension families through one runtime:

```text
legacy Mihon 1.4 extension
  -> Rx fetch* methods + HttpSource request/parser helpers

Keiyoushi v16 / KeiSource extension
  -> suspend get* methods + final getMangaUpdate bridge
  -> source-owned CompressionInterceptor(Brotli, Gzip, Zstd)
```

The official Mihon host and current Keiyoushi extension build are the
reference implementations. Their shared runtime boundary is OkHttp 5.4.0,
Okio 3.17.0, the concrete Mihon default-client interceptors, and the memo
fields on `SManga`/`SChapter`.

## 1. Confirmed Data Flow

```text
History projection
  -> MainActivity.resolveDetailsOriginForContent
  -> DetailsOrigin.EntityGraph(entityId, initialProjectionLocalMangaId)
  -> DetailsViewModel.applyEntityContext
  -> real local projection details / source options
  -> MihonMangaRepository.getDetailsImpl
```

For a loaded extension, the runtime path is:

```text
APK -> child-first loader
   -> shared host eu.kanade.tachiyomi / okhttp3 classes
   -> Injekt NetworkHelper
   -> source.client (KeiSource may clone and configure it)
   -> MihonMangaRepository combined update boundary
```

Kototoro intentionally delegates `okhttp3.*`, `okio.*`, Kotlin, and Mihon API
packages to the host. Therefore the host ABI, rather than an extension-bundled
library, is authoritative.

The entry log confirms that the first edge preserves the real projection ID.
During entity initialization, `DetailsViewModel` can create a temporary
synthetic Content whose ID is the Work entity ID and whose source is `Entity
Graph`. If that value reaches chapter or related-content consumers before the
real projection is loaded, `ContentRepositoryFactory` correctly has no Mihon
provider for it and returns `EmptyContentRepository`.

The refresh path independently reaches `MihonMangaRepository.getDetailsImpl`,
where the new combined Mihon update API is called with a snapshot derived from
the current Content and an empty chapter list. AllManga rejects that input with
an obfuscated NPE.

## 2. Design Boundaries

### Projection handoff

- Treat `initialProjectionLocalMangaId` as the authoritative initial local
  projection for an Entity Graph detail route.
- Resolve the projection from the local manga table/entity bindings before
  synthetic header data is consumed by chapter, related, or provider flows.
- Keep synthetic Entity Graph content limited to metadata placeholder display;
  it must not be used as a ContentRepository source when a real projection is
  available.
- Keep the current content-type and Space filtering rules intact.

### Mihon API compatibility

- Audit the recent combined-update migration and the legacy/suspend bridging
  contract before changing the call site.
- Supply the Mihon source with a valid manga snapshot and the chapters it can
  safely update, or use the established legacy details/chapter path when the
  combined API cannot accept the current state.
- Preserve `MihonRequestContext`, source tags, exception unwrapping, and chapter
  snapshot caching.
- Do not catch plugin NPEs and silently return an empty details object.

### Runtime network compatibility

- Upgrade the host OkHttp/Okio family to the versions used by official Mihon
  and Keiyoushi v16. Keep `okhttp-brotli`, `okhttp-zstd`, and the transitive
  zstd KMP artifact on the same version line.
- Construct the Mihon default client with concrete
  `UncaughtExceptionInterceptor`, `UserAgentInterceptor`, and
  `CloudflareInterceptor` entries. Preserve their required order before
  copied Kototoro interceptors.
- Filter Kototoro's Brotli and legacy Gzip interceptors from both interceptor
  lists before exposing the client. `KeiSource` installs its own compression
  interceptor and validates this contract.
- Preserve a separate Brotli-enabled compatibility client through the
  existing `cloudflareClient` property for legacy `HttpSource` extensions;
  never expose that client as `NetworkHelper.client`, because `KeiSource`
  rejects Brotli in the default chain.
- Copy the full base-client settings required by sources (timeouts, cache,
  cookies, DNS, proxy, TLS, dispatcher, connection pool, redirect and retry
  policy) without copying incompatible compression handlers.

### Source API compatibility

- Keep the current legacy `fetch*` request/parser path one-way and nonrecursive.
- Keep the combined suspend API as the repository boundary. A source that
  overrides `getMangaUpdate`/`fetchMangaUpdate` must not be forced through
  legacy helpers.
- Retain compatibility defaults for old source implementations while exposing
  the v16 fields and methods needed by current Keiyoushi sources.
- Preserve `memo` in every `SManga`/`SChapter` copy and model conversion.

## 3. Compatibility and Risk

- History, favourites, search, and direct source routes must continue to share
  the same Entity Graph identity but retain their requested projection.
- A missing local projection must remain a diagnosable unsupported-source or
  unavailable-content state; it must not be replaced with an arbitrary entity
  ID.
- The fallback must not cause duplicate network requests for extensions that
  already implement the combined API correctly.
- An OkHttp upgrade affects all Kototoro network clients, image loading,
  Cloudflare handling, and dynamic extension code. Validate the complete app
  dependency graph rather than changing only the Mihon adapter.
- Kotlin/runtime library differences are a secondary risk because the loader
  shares Kotlin packages. Avoid a broad Kotlin upgrade unless a reproducible
  v16 runtime failure requires it; first align the public API and network ABI.

## 4. Verification Strategy

1. Unit-test projection selection when an Entity Graph origin has both a real
   initial projection and a synthetic entity header.
2. Unit-test that synthetic Entity Graph content is excluded from provider and
   related-content seeds when a real local projection exists.
3. Add Mihon adapter tests for a combined-update source receiving initialized
   input and for the compatibility fallback.
4. Add a KeiSource-style fixture that uses source-owned compression and a
   non-AllManga memo key, proving the contract is generic.
5. Run the existing details/Mihon unit tests and Debug compilation, including
   dependency resolution after the OkHttp upgrade.
