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
| Guard-native JAR spiders | Not reliably supported locally; classified by `TVBoxSupportStatusClassifier` and `TVBoxRuntimeDiagnostics` | Do not treat as ordinary JAR failures; isolate, classify, and degrade instead of repeatedly crashing local runtime |

## Ordinary JAR vs Guard-Native JAR

Ordinary TVBox JAR spiders are Java/Kotlin bytecode loaded through a `DexClassLoader`, instantiated by class name, initialized with a TVBox-style context, and called through methods such as `homeContent`, `categoryContent`, `detailContent`, `searchContent`, `playerContent`, and `proxyLocal`.

Guard-native JARs are different. They may ship encrypted guard payloads and native libraries, then delegate real spider creation through JNI/native code. Previous investigation showed the failure point had already moved past simple Java-layer issues such as missing `Init.init(context)` or a null `Context.getCacheDir()`. The remaining failures entered native/JNI crash territory.

Therefore:

- Do not classify Guard-native failures as "ordinary JAR runtime missing one more stub."
- Do not repeatedly execute a known fatal Guard source in the main runtime path.
- Keep the local JAR runtime useful for ordinary spiders.
- Surface Guard-native limitations explicitly in logs and user-facing support status.

## Already Tried

The project has already explored more than one approach:

- importing TVBox JSON sources as normalized per-site sources
- supporting multi-repository TVBox JSON files
- adding a QuickJS bridge for `type = 4` (`TVBoxQuickJsSpiderRuntime.kt`)
- adding a local `DexClassLoader` runtime for `type = 3` / `csp_*` (`TVBoxJarSpiderRuntime.kt`)
- aligning the Java-level loading sequence with TVBoxOS-style shells
- adding TVBox / CatVod host compatibility stubs
- implementing structured support status classification (`TVBoxSupportStatusClassifier`)
- implementing structured failure diagnostics (`TVBoxRuntimeDiagnostics`)
- experimenting with an isolated companion / worker process path
- comparing Guard behavior against TVBoxOS-style loading

The important conclusion from that work is narrow: local Java-layer alignment remains valuable for ordinary spiders, but Guard-native JARs are a separate native compatibility problem.

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
- limited for Guard-native JARs

Avoid promising full compatibility with every TVBox repository. Many public TVBox lists mix direct sources, CMS sources, ordinary spiders, JavaScript spiders, and Guard-native spiders in one file, so a repository can be partially usable even when some entries are not.

## Key Files

- Runtime: `TVBoxSpiderRuntime.kt`, `TVBoxJarSpiderRuntime.kt`, `TVBoxQuickJsSpiderRuntime.kt`, `TVBoxSpiderRuntimeFactory.kt`
- Repository: `TVBoxRepository.kt`
- Playback: `TVBoxPlayback.kt`
- Diagnostics: `TVBoxRuntimeDiagnostics.kt`, `TVBoxSupportStatusClassifier.kt`
- All under `core/parser/tvbox/`
