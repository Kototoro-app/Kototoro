# Tsundoku / NovelSourcery offline self-built test APK fixtures (T0.3)

Phase 0 / T0.3 of `docs/architecture/tsundoku-extension-integration-plan-2026-08.md`:
offline-compilable, self-built Android APKs that reproduce the **real** NovelSourcery
extension morphologies so the host's Tsundoku classifier / loader / per-source validation can
be tested without depending on an online extension repository.

All four APKs are built fully offline (`--offline`), compile against the **host ABI jar**
(never copied ABI sources), and mirror the real-extension manifests byte-for-byte in every key
the host reads (verified against the real downloaded
`eu.kanade.tachiyomi.novelextension.en.bakatsuki` 1.4.2 and
`eu.kanade.tachiyomi.novelextension.en.novelfull` 1.6.11 APKs).

## Modules

| Module | versionName | metadata class | Morphology / purpose |
|---|---|---|---|
| `tsundoku-14-single` | `1.4.1` | `.SingleNovel` | extensions-lib 1.4 **direct source**: `Source` + `isNovelSource=true`, no 1.6-only members (`SourceTracker`/`RateLimited`/`fetchPageText`/`RefreshContext` all absent — defaults/throw). |
| `tsundoku-14-factory` | `1.4.2` | `.FactoryNovel` | extensions-lib 1.4 **`SourceFactory`**: `createSources()` returns a legal novel source **and** a deliberately non-novel manga source, so the host proves per-source rejection keeps the legal source while recording a `TachiyomiSourceRejection` (plan §6.3 / T2A.2). |
| `tsundoku-16-suspend` | `1.6.1` | `.NovelSuspendV16` | extensions-lib 1.6 full surface: `isNovelSource` + `supportsLatest` + `suspend fetchPageText(Page): String` + `SourceTracker` (chapter/favorite callbacks) + `RateLimited` + `getChapterList(manga, RefreshContext)` delta-refresh. |
| `tsundoku-ambiguous` | `1.4.3` | `.AmbiguousNovel` | **AMBIGUOUS rejection path**: declares BOTH `tachiyomi.novelextension` and `tachiyomi.extension` (plus both class metadata keys); the strict `TachiyomiApkClassifier` must report `Ambiguous` without loading any class (plan §7.2). |

Build types: `debug` (what the host loads) and a no-op `release`. No `applicationIdSuffix`,
so the APK `package` is exactly the real-extension-style package name.

## How the build works

1. `app/build.gradle` registers the `:app:exportTachiyomiAbi` Jar task: it runs
   `compileDebugKotlin` and packs the compiled `eu/kanade/**` host ABI classes
   (debug variant is unobfuscated; R8 only runs for release/nightly) into
   `app/build/libs/tachiyomi-abi.jar`. It self-checks the novel ABI members exist.
2. Each fixture module depends on that jar with **`compileOnly`**
   (`files(...).builtBy(':app:exportTachiyomiAbi')`) plus the cached signature-only transitive
   deps the ABI references (`libs.rxjava`, `kotlin-stdlib`, `kotlinx-coroutines-android`,
   `kotlinx-serialization-json`) — all already in the offline Gradle cache, no new downloads.
3. At runtime the host's `TachiyomiApkClassLoaderPolicy` delegates
   `eu.kanade.tachiyomi.source.*` (and `.model/online/network/util`) to the host, so the ABI
   must NOT be bundled in the dex — `compileOnly` keeps it out.

The fixtures are written in plain **Java** on purpose: a Kotlin interface compiles its
default-method surface to JVM `default` methods, so a Java class only needs to implement the
abstract members and the exact JVM signatures of the Kotlin `suspend` functions (note the
covariance: `suspend fun getChapterList(...): List<SChapter>` surfaces in Java as
`Continuation<? super List<? extends SChapter>>`).

## Build + verify (offline)

```bash
cd "$(repo root)"
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  ./gradlew :app:exportTachiyomiAbi --offline --no-daemon
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  ./gradlew :fixtures:tsundoku-14-single:assembleDebug \
            :fixtures:tsundoku-14-factory:assembleDebug \
            :fixtures:tsundoku-16-suspend:assembleDebug \
            :fixtures:tsundoku-ambiguous:assembleDebug --offline --no-daemon

AAPT=$(ls -d /d1/android-sdk/build-tools/*/ | sort -V | tail -1)aapt2
for apk in fixtures/*/build/outputs/apk/debug/*.apk; do "$AAPT" dump badging "$apk"; echo; done
```

APK outputs (default `debug` path):

```
fixtures/tsundoku-14-single/build/outputs/apk/debug/tsundoku-14-single-debug.apk
fixtures/tsundoku-14-factory/build/outputs/apk/debug/tsundoku-14-factory-debug.apk
fixtures/tsundoku-16-suspend/build/outputs/apk/debug/tsundoku-16-suspend-debug.apk
fixtures/tsundoku-ambiguous/build/outputs/apk/debug/tsundoku-ambiguous-debug.apk
```

## Ground truth the manifests reproduce

Verified with `aapt2 dump xmltree` on the real downloaded NovelSourcery APKs:

- `<uses-feature android:name="tachiyomi.novelextension"/>` with **no `required` attribute**
  (defaults to `required=true`, so the feature lands in `PackageInfo.reqFeatures`, the exact
  field `TachiyomiApkClassifier` checks).
- `tachiyomi.novelextension.class` = **relative** `.ClassName`; the loader resolves it against
  the package name (`ExternalExtensionSourceLoaderSupport.resolveSourceClassNames`).
- `tachiyomi.novelextension.nsfw` (int), `tachiyomi.novelextension.novel` (int 1),
  `tachiyomix.name` (string), `tachiyomix.contentWarning` (int), `tachiyomix.extensionLib`
  (float `1.4`/`1.6`) — the float matches `versionName.substringBeforeLast('.')`, and the
  metadata override wins in `TachiyomiApkLoaderRuntime.extractExtensionInfo`.
- Real package prefix `eu.kanade.tachiyomi.novelextension.{lang}.{slug}` (`zh.kototoro_...`).
- versionCode mirrors the NovelSourcery convention (patch number: `1.4.2` → `2`).

## Notes / non-goals

- The 1.4 fixtures deliberately do NOT override `fetchPageText` — exactly what a real
  1.4-compiled source does (the member didn't exist in lib 1.4; the host default throws).
  The 1.6 fixture is where the text path is exercised.
- No launcher activity, no icon, no resources: `com.android.application` assembles these fine;
  the host only inspects manifest + dex.
- Never commit `app/build/libs/tachiyomi-abi.jar` or any `build/` output (gitignored).
