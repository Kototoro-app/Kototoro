# TVBox Runtime Compatibility

This page records the current engineering position for TVBox support in Kototoro. It is intentionally scoped to runtime compatibility, not UI parity with TVBox shells.

## Current Position

Kototoro has TVBox support across multiple layers:

- TVBox JSON import for single-repository and multi-repository configurations
- per-site normalized storage instead of storing only the original whole JSON document
- TVBox source management in the unified source management screen
- `type = 4` routing through a QuickJS-based runtime (`TVBoxQuickJsSpiderRuntime.kt`)
- `type = 3` / `csp_*` routing through a local JAR spider runtime (`TVBoxJarSpiderRuntime.kt`)
- Support status classification via `TVBoxSupportStatusClassifier` (DIRECT, PARTIAL_RUNTIME, QUICKJS_PARTIAL, BRIDGEABLE, SPIDER_BRIDGE, ORDINARY_JAR, GUARD_NATIVE)
- Structured failure diagnostics via `TVBoxRuntimeDiagnostics` with 10 failure categories
- fallback handling for direct media, playlists, text live lists, and simple CMS-style APIs

The unresolved work is compatibility depth. In particular, ordinary JAR spiders and Guard-native JAR spiders must be treated as different classes of runtime behavior.

## Support Matrix

| TVBox source shape | Current status | Expected direction |
| --- | --- | --- |
| Direct media URLs | Supported when the imported config exposes usable playback URLs | Keep stable and avoid unnecessary spider execution |
| M3U / text live lists / simple playlists | Supported for simpler configurations | Improve parsing coverage and diagnostics |
| Simple CMS-style APIs | Partially supported through fallback candidates | Improve candidate detection and per-request logs |
| `type = 4` JavaScript sources | QuickJS bridge (`TVBoxQuickJsSpiderRuntime.kt`) with basic bridge support | Fill gaps around `cat.js`, dependency loading, `js2Proxy`, modules, and unsupported bytecode formats |
| Ordinary `type = 3` / `csp_*` JAR spiders | Local runtime (`TVBoxJarSpiderRuntime.kt`) follows TVBoxOS-style Java lifecycle | Keep improving host ABI shims, missing classes, proxy handling, and diagnostics |
| Guard-native JAR spiders | Supported when their native loader follows the standard `Init.init(Context)` lifecycle | Keep protection detection narrow and classify actual JNI failures separately |

## Ordinary JAR vs Guard-Native JAR

Ordinary TVBox JAR spiders are Java/Kotlin bytecode loaded through a `DexClassLoader`, instantiated by class name, initialized with a TVBox-style context, and called through methods such as `homeContent`, `categoryContent`, `detailContent`, `searchContent`, `playerContent`, and `proxyLocal`.

Guard-native JARs are different. They may ship encrypted guard payloads and native libraries, then delegate real spider creation through JNI/native code. Their outer `Init.init(Context)` is often essential: it seeds the application context, decrypts the payload, and creates the inner `DexClassLoader` used by `DexNative.getSpider`. Replacing that inner loader with the outer JAR loader is type-correct but behaviorally invalid.

Therefore:

- Run the real `Init.init(Context)` for ordinary and native Guard JARs.
- Scan the JAR DEX before initialization for the known `Init*` plus `Process.killProcess` protection pattern.
- If the current host package is not explicitly allowed by that Init, skip the original method and reconstruct only the known safe state (`Init.get().c`, cloud-disk naming, and Go proxy startup).
- Keep actual native/JNI failures distinct in diagnostics instead of assuming every Guard JAR is unsupported.

## Init Strategies

The JAR runtime selects one of two explicit initialization strategies:

| Strategy | Detection | Behavior |
| --- | --- | --- |
| Standard | No protected Init pattern, or the current package is allowed | Invoke the JAR's `Init.init(Context)` so native loaders can decrypt and create their inner runtime |
| Protected | `Init*` invokes `Process.killProcess` and `Init.init` does not allow the current package | Do not execute the original Init; set the known context field and invoke optional post-init hooks |

The detector supports plain comma-separated package lists and the fixed AES/CBC package-list encoding found in this ecosystem. Malformed or unknown input defaults to standard initialization; failures remain visible through runtime diagnostics.

## CatVod Host ABI

Package-name compatibility is only one part of JAR compatibility. A spider may link against host fields, methods, Android classes, or third-party libraries before its own `init` method runs. Kototoro therefore maintains the CatVod host surface independently from protected-Init handling.

The current Spider lifecycle is:

1. instantiate `com.github.catvod.spider.<ClassName>`
2. assign `Spider.siteKey`
3. call `Spider.initApi(SpiderApi)` with the JAR thread context class loader installed
4. call `Spider.init(Context, ext)`, falling back to `Spider.init(Context)` only when necessary

Type 3 detail and playback follow the FongMi/TVBoxOSC data flow. The host parses
`vod_play_from` and `vod_play_url` from the detail response, then calls
`playerContent(flag, episodeId, vipFlags)` before treating the episode ID as a direct URL. A detail
response may omit `vod_id`; the ID passed to `detailContent` remains the content identity while the
returned playback groups are still accepted.

The base ABI includes `Spider.empty`, `Spider.mContext`, two- and three-argument search, `client`, `safeDns`, `proxy`, `proxyLocal`, and `action`. The default three-argument search delegates to the legacy two-argument method so older spiders do not silently return an empty page.

TVBox configuration entries are ordinary list items carrying an `action` field. They must not be
opened as detail pages. Kototoro preserves the opaque action in source-owned metadata, invokes
`Spider.action(action)` when the item is selected, displays the returned `msg`, and refreshes the
current list. Cloud-drive login forms, cookie/token clearing, ordering controls, clipboard access,
WebView flows, and QR-code push pages remain owned by the Spider JAR. The host supplies the current
foreground Activity through the TVBox `App`/`AppManager` bridge so those JAR-provided controls have
a valid window context.

`SpiderApi` currently exposes proxy address/port lookup, spider logging, screen orientation, bounded parallel HTTP requests, and `webParse` URL construction. These methods are host shims; they should remain small and must not introduce a second application architecture inside the CatVod package.

The proxy host reuses `VideoLocalCacheProxy` rather than starting a second server. Each loaded JAR and instantiated Spider receives an isolated dynamic endpoint:

- `SpiderApi.getAddress(local)` returns that endpoint's root address.
- `com.github.catvod.Proxy.getUrl(local)` appends the compatible `/proxy` subpath.
- requests containing `do` are dispatched to that Spider's `proxyLocal` method.
- requests containing `go` are dispatched to the loaded JAR's static `com.github.catvod.spider.Proxy.proxy` method.

The endpoint binding is installed together with the JAR thread context class loader for static Init, Spider calls, and NanoHTTP proxy callbacks. It uses inheritable thread-local state so worker threads created by a Spider retain the correct endpoint without introducing TVBox's process-wide "recent JAR" race.

Each usable JAR is logged with its final SHA-256 fingerprint before class loading. This is diagnostic provenance rather than enforced pinning: existing `;md5;` source metadata remains supported, while operators can identify remote JAR content that changed without changing its URL.

## Already Tried

The project has already explored more than one approach:

- importing TVBox JSON sources as normalized per-site sources
- supporting multi-repository TVBox JSON files
- adding a QuickJS bridge for `type = 4` (`TVBoxQuickJsSpiderRuntime.kt`)
- adding a local `DexClassLoader` runtime for `type = 3` / `csp_*` (`TVBoxJarSpiderRuntime.kt`)
- aligning the Java-level loading sequence with TVBoxOS-style shells
- replacing unconditional Init skipping and broad reflective field injection with explicit standard/protected strategies
- adding TVBox / CatVod host compatibility stubs
- implementing structured support status classification (`TVBoxSupportStatusClassifier`)
- implementing structured failure diagnostics (`TVBoxRuntimeDiagnostics`)
- experimenting with an isolated companion / worker process path
- comparing Guard behavior against TVBoxOS-style loading

The important conclusion from that work is narrow: Guard-native does not itself imply incompatibility. Some Guard JARs only require their real Init lifecycle, while host-restricted Init implementations require a targeted bypass.

## Diagnostic Policy

When a TVBox source fails, classify the failure before changing runtime code. `TVBoxRuntimeDiagnostics` provides structured classification:

- `json_import`: the source JSON could not be fetched, parsed, or normalized
- `multi_repo`: a child repository failed to resolve or produced no valid sites
- `direct_media`: the config exposed a media URL that could not be played directly
- `cms_fallback`: CMS candidate detection or CMS request failed
- `quickjs_missing_feature`: the JavaScript runtime lacks a needed bridge feature
- `ordinary_jar_missing_class`: a local JAR spider references a missing host class
- `ordinary_jar_missing_method`: a local JAR spider references an incompatible host method
- `ordinary_jar_proxy`: `proxy` / `proxyLocal` handling is incomplete
- `ordinary_jar_runtime`: general JAR runtime failure (timeout, etc.)
- `guard_native`: a Guard-native spider hits native/JNI failure or is known to require native guard behavior

Support status is pre-classified by `TVBoxSupportStatusClassifier`:
- `DIRECT`: direct media URL, no spider required
- `PARTIAL_RUNTIME`: CMS fallback candidate
- `QUICKJS_PARTIAL`: type=4 JavaScript source
- `BRIDGEABLE`: playable/CMS candidate with spider artifacts
- `SPIDER_BRIDGE`: spider artifacts only, no playable candidate
- `ORDINARY_JAR`: ordinary JAR spider (type=3 or csp_*)
- `GUARD_NATIVE`: Guard-native JAR (detected by guard/dexnative/basespiderguard/wex keywords)

This keeps fixes small and prevents unrelated runtime paths from being destabilized.

## Product Guidance

TVBox support should be described as a compatibility spectrum:

- stable for direct media, playlists, and simpler JSON/CMS sources
- improving for QuickJS and ordinary JAR spiders
- improving for Guard-native JARs that follow the standard loader lifecycle

Avoid promising full compatibility with every TVBox repository. Many public TVBox lists mix direct sources, CMS sources, ordinary spiders, JavaScript spiders, and Guard-native spiders in one file, so a repository can be partially usable even when some entries are not.

## Key Files

- Runtime: `TVBoxSpiderRuntime.kt`, `TVBoxJarSpiderRuntime.kt`, `TVBoxQuickJsSpiderRuntime.kt`, `TVBoxSpiderRuntimeFactory.kt`
- Repository: `TVBoxRepository.kt`
- Playback: `TVBoxPlayback.kt`
- Diagnostics: `TVBoxRuntimeDiagnostics.kt`, `TVBoxSupportStatusClassifier.kt`
- All under `core/parser/tvbox/`
