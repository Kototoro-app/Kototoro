# Kototoro Architecture Review

## Purpose

This document consolidates a high-level architectural review of Kototoro based on the current documentation and codebase. It is meant to help contributors understand what the project already does well, where the architectural center of gravity is, and which structural risks are most worth addressing next.

This is not an implementation plan for a single feature. It is a project-level systems view.

## Executive Summary

Kototoro is no longer just a manga reader fork with a few add-ons. The codebase is clearly evolving toward a unified Android content platform that brings together:

- manga
- novels
- video
- local OCR + translation
- external extension ecosystems
- WebDAV backup and sync
- local/offline content workflows

The most important architectural fact about the project is this:

> Kototoro's core value comes from unifying many content types and source ecosystems behind one app-level reading and synchronization workflow.

That direction is strong and valuable. It also means the project must keep investing in architectural boundaries, because functionality is now broad enough that uncontrolled growth will eventually hurt maintainability.

## Product Direction Visible in the Docs

The public documentation already defines the product as an all-in-one Android app for manga, novels, and video.

Relevant references:

- [`docs/index.md`](../index.md)
- [`docs/getting-started.md`](../getting-started.md)
- [`docs/reader-features.md`](../reader-features.md)
- [`docs/source-integrations.md`](../source-integrations.md)

Across those pages, the recurring product promises are consistent:

1. one app for manga, novels, and video
2. local OCR + translation inside the reading flow
3. broad source compatibility through built-in and external ecosystems
4. multi-device backup and sync through WebDAV

This is important because the codebase largely matches that stated direction. The architecture is not accidental anymore. It is converging on a broad, unified content platform.

## Current Architectural Style

Kototoro is best described as a modular monolith with feature-oriented packaging.

It is not a strict clean-architecture codebase, and it is not split into many Gradle feature modules. Instead, the app mainly organizes around feature domains, with local layering inside each domain.

Common pattern:

- `feature/data`
- `feature/domain`
- `feature/ui`
- shared infrastructure in `core/*`

Examples visible in the current package layout include:

- `backups/*`
- `bookmarks/*`
- `core/*`
- `local/*`
- `mihon/*`
- `aniyomi/*`
- `reader/*`
- `sync/*`
- `video/*`
- `settings/*`
- `extensions/*`

This is a pragmatic Android architecture. It scales better than a flat package layout and keeps domain concepts close to their UI and persistence logic.

## Technology Stack

The build configuration shows a modern Android stack with strong native and compatibility capabilities.

Key references:

- [`app/build.gradle`](../../app/build.gradle)
- [`build.gradle`](../../build.gradle)

Main technologies in use:

- Kotlin
- Android View system and ViewBinding
- Hilt for DI
- Room for local persistence
- WorkManager for background work
- OkHttp / Okio for networking
- Coil for image loading
- Media3 and mpv for video playback
- ML Kit, ONNX Runtime, NCNN, LiteRT, DJL tokenizer for OCR / translation / model execution
- QuickJS and Rhino for JavaScript execution
- ACRA for crash reporting

This stack is consistent with the product ambition. The project is not just a UI shell over remote APIs. It contains meaningful local processing, compatibility runtime work, and offline capabilities.

## Application-Level Composition

The application bootstrap logic is centralized in:

- [`app/src/main/kotlin/org/Kototoro-app/Kototoro/core/BaseApp.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/core/BaseApp.kt)

`BaseApp` is currently responsible for:

- OkHttp Android initialization
- crash reporting setup
- night mode setup
- TLS compatibility provider setup
- lifecycle callback registration
- database observer setup
- WorkManager integration
- local storage change observation
- Mihon extension manager initialization
- Aniyomi extension manager initialization

This makes `BaseApp` the app-level composition root.

### Strength

The app has one obvious place where global infrastructure is wired together.

### Risk

As more cross-cutting capabilities are added, `Application` can easily become a catch-all orchestrator. That risk is still manageable, but the trend should be watched.

## Main UI and Navigation Layer

Main application entry:

- [`app/src/main/kotlin/org/Kototoro-app/Kototoro/main/ui/MainActivity.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/main/ui/MainActivity.kt)

`MainActivity` already functions as a unified shell for multiple content workflows. The current behavior includes:

- multi-fragment primary navigation
- integrated search
- foldable support
- startup wiring for sync and restore-related logic
- coordination of Explore, Favorites, Feed, History, and related screens

This is an important architectural signal: the app shell is already treating the project as a unified content hub rather than a single-purpose manga entry screen.

## Reader Layer: One of the Strongest Parts of the Project

The reader subsystem is one of Kototoro's clearest strengths.

Important entry points:

- Manga reader: [`reader/ui/ReaderActivity.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/ui/ReaderActivity.kt)
- Novel reader: [`reader/novel/NovelReaderActivity.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/novel/NovelReaderActivity.kt)
- Video player: [`video/ui/VideoPlayerActivity.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/video/ui/VideoPlayerActivity.kt)

### Manga reader

The manga reader already contains substantial internal architecture:

- standard pager mode
- reversed pager mode
- double-page mode
- webtoon mode
- vertical mode
- zoom controls
- scroll timer
- translation toggles
- foldable-aware layout behavior

Supporting packages include:

- `reader/ui/pager/standard/*`
- `reader/ui/pager/reversed/*`
- `reader/ui/pager/doublepage/*`
- `reader/ui/pager/webtoon/*`
- `reader/ui/pager/vertical/*`

### Novel reader

The novel reader is not just a thin adaptation of manga pages. It has dedicated loading and rendering paths, including EPUB-specific support.

### Video player

The video subsystem is also substantial, not peripheral. Current code indicates support for:

- mpv-based playback
- danmaku integration
- playback history restoration
- PiP
- gesture control
- renderer and super-resolution settings
- chapter/episode progression behavior

### Assessment

The most important positive pattern here is that Kototoro did not try to force manga, novels, and video into one fake universal renderer. The project has a unified product shell, but separate consumption systems where it matters.

That is the right choice.

## Persistence Layer

Main database definition:

- [`app/src/main/kotlin/org/Kototoro-app/Kototoro/core/db/MangaDatabase.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/core/db/MangaDatabase.kt)

Even though the database is still named `MangaDatabase`, its real scope is much broader. It now stores or coordinates data for:

- manga and chapters
- tags
- history
- favorites and favorite categories
- reader preferences
- track logs and tracker state
- suggestions
- bookmarks
- scrobbling
- sources
- stats
- local manga index
- EPUB chapter mapping
- JSON sources
- external extension repositories

The explicit migration list is long and maintained, which is a strong sign that the project is already in a mature, stateful phase rather than a prototype phase.

### Strength

- schema evolution is being treated seriously
- new subsystems are integrated into persistent storage instead of being hidden in ad hoc preference blobs

### Risk

The name `MangaDatabase` now understates the actual responsibility. Over time, this becomes a conceptual mismatch for contributors.

## Content Source Abstraction: The Core Architectural Asset

The most strategically important abstraction in the app is the content repository layer:

- [`app/src/main/kotlin/org/Kototoro-app/Kototoro/core/parser/MangaRepository.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/core/parser/MangaRepository.kt)

Despite the name, this interface has become the app's general content-source access contract for many workflows.

It encapsulates source responsibilities such as:

- list loading
- details loading
- page loading
- chapter content loading
- filter options
- request construction
- related content lookup

It also explicitly models pagination differences via `ListPagingMode`, which shows a good awareness that upstream ecosystems do not all behave alike.

### Why this matters

This abstraction is what allows the rest of the app to work with:

- built-in parsers
- local content
- Mihon sources
- Aniyomi sources
- Legado sources
- future TVBox-backed sources

without making every screen source-aware.

That is a major architectural win.

## Repository Factory and Source Resolution

The factory inside `MangaRepository` is powerful, but it is also one of the most important places to watch going forward.

It currently performs multiple roles:

- source type inspection
- source-name protocol resolution
- JSON source lookup from persistence
- Mihon source resolution
- Aniyomi source resolution
- repository creation
- repository caching

### Strength

This gives Kototoro a single place to map abstract source identities to executable repositories.

### Risk

This is starting to look like a future “super factory” problem. As more ecosystems are added, the current structure can become a maintenance bottleneck.

### Recommendation

Over time, this area should likely be split into clearer responsibilities:

1. `SourceResolver`
2. `RepositoryProvider`
3. `RepositoryInstanceCache`

The current code is still workable, but it is a natural pressure point.

## Local Content as a First-Class Subsystem

Main implementation:

- [`app/src/main/kotlin/org/Kototoro-app/Kototoro/local/data/LocalMangaRepository.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/local/data/LocalMangaRepository.kt)

The local content stack is substantial. It handles:

- local directory scanning
- local manga metadata and chapter parsing
- filtering and sorting
- EPUB compatibility and migration paths
- chapter deletion
- mapping local content back to remote content where possible
- local outputs and storage management

There is also an explicit EPUB sub-area under `local/epub/*`.

### Assessment

This is not “bonus functionality.” It is part of the app's real architecture. That matters because heavy reader users often expect local import, offline workflows, and format migration support.

### Risk

`LocalMangaRepository` carries several kinds of responsibility at once:

- repository interface implementation
- local format orchestration
- migration behavior
- local-to-remote mapping

That suggests future refactoring value, especially if EPUB and local novel support continue growing.

## External Ecosystem Compatibility Layer

The external ecosystem work is one of Kototoro's most ambitious and most valuable directions.

Relevant code and docs:

- [`mihon/MihonExtensionManager.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/mihon/MihonExtensionManager.kt)
- [`aniyomi/AniyomiExtensionManager.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/aniyomi/AniyomiExtensionManager.kt)
- [`extensions/repo/ExternalExtensionRepoRepository.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/extensions/repo/ExternalExtensionRepoRepository.kt)
- [`settings/sources/extensions/ExtensionsBrowserViewModel.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/settings/sources/extensions/ExtensionsBrowserViewModel.kt)
- [`docs/architecture/architecture-roadmap.md`](./architecture-roadmap.md)

### Important observation

The project is no longer treating Mihon and Aniyomi integration as “scan whatever APKs happen to be installed.” It is evolving toward a real in-app extension management platform with:

- repository URL persistence
- remote catalog fetching
- extension availability state
- install/update workflows
- trust and signature considerations
- unified browser behavior across manga and anime extensions

That is a major strategic differentiator.

### Strength

The extension ecosystem is being elevated to a product capability, not treated as a fragile compatibility afterthought.

### Risk

The Mihon and Aniyomi manager implementations are still highly parallel. Their current symmetry is understandable, but if that duplication continues to spread, the maintenance cost will multiply.

### Recommendation

A future generalized external-extension runtime abstraction would likely be valuable, even if full generic unification is not done immediately.

## OCR and Translation Pipeline

Historical design reference:

- [Archived OCR Pipeline V2](../archive/ocr-pipeline-v2.md)

Current OCR interface:

- [`reader/translate/domain/ReaderOcrEngine.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/reader/translate/domain/ReaderOcrEngine.kt)

The OCR and translation subsystem is one of the deepest technical areas in the codebase. The current design is not just “run OCR and show text.” It includes explicit architectural thinking around:

- full-page OCR
- ROI-aware OCR
- bubble detection
- fallback page OCR
- translation caching
- grouping and rendering semantics
- future metrics and debugability

The presence of multiple model managers also shows that the project is treating OCR execution as an internal platform capability, not a single hardcoded model path.

### Strength

Kototoro's OCR work is meaningfully integrated into the reader experience, which is one of the most distinctive parts of the product.

### Risk

This subsystem is now complex enough to become its own maintenance universe. If not bounded carefully, it can start dictating the structure of the reader instead of remaining a powerful enhancement layer.

### Recommendation

Keep the reader core stable and treat OCR/translation as an enhancement layer with explicit fallback and observability, not as a hard dependency for basic reading correctness.

## Network Layer

The network stack is broad and clearly built for high-variance source compatibility.

Relevant areas include:

- `core/network/*`
- `core/network/jsonsource/*`
- `core/network/webview/*`

Observed responsibilities include:

- common headers
- rate limiting
- DoH
- cookie management
- Cloudflare handling
- image proxy support
- JSON source-specific networking
- Legado HTTP client support
- WebView-assisted continuation behavior

### Assessment

This is not a normal app networking layer. It is effectively a compatibility networking platform for unstable upstream sites and heterogeneous source protocols.

That is necessary for the product direction, but it also means debugging and observability must remain a priority.

## Sync and Backup Systems

Sync and backup are first-class parts of the product.

Important references:

- [`sync/domain/SyncController.kt`](../../app/src/main/kotlin/org/Kototoro-app/Kototoro/sync/domain/SyncController.kt)
- backup packages under `backups/*`
- user docs such as [`docs/webdav-sync.md`](../webdav-sync.md)

`SyncController` shows a real integration with:

- AccountManager
- ContentResolver sync
- Room invalidation tracking

At the same time, Kototoro also supports WebDAV backup and restore workflows, plus automation around backup scheduling.

### Strength

The project treats portability and state persistence seriously, which is a major benefit for reader users with multiple devices.

### Risk

The codebase currently contains multiple concepts that can all be perceived as “sync” by both users and contributors:

- Android account/content sync
- WebDAV backup/restore
- auto-restore behavior
- periodical backup
- external upload integrations

### Recommendation

The architecture and docs should keep drawing a sharp line between:

1. state synchronization
2. backup and restore

These are related but not the same system.

## What the Project Already Does Well

### 1. The product direction and the code mostly agree

Kototoro's documentation and implementation are aligned more than in many projects of similar breadth.

### 2. The source abstraction is strong

The repository abstraction is the core enabling structure for the project's multi-ecosystem strategy.

### 3. Reader experiences are deep, not shallow

Manga, novels, and video each have meaningful internal design instead of being forced into one weak generic model.

### 4. External ecosystem work is strategically smart

Unifying Mihon and Aniyomi extension management is a high-value direction.

### 5. Local and portable workflows matter in the architecture

Offline and migratable reading behavior is being treated as an architectural concern, not a side feature.

### 6. Documentation discipline is improving

The growing architecture and handoff docs are a strong positive signal.

## Main Architectural Risks

## 1. Capability accretion can turn the app into an unbounded mega-system

Kototoro now includes:

- multiple content types
- multiple parser ecosystems
- OCR and translation
- local import/export
- extension repository management
- sync and backup systems
- JavaScript runtime support
- compatibility networking

All of these are individually reasonable, but together they create real architecture-management pressure.

### Why this matters

Without stronger platform boundaries, every new feature will start cutting across too many layers.

## 2. “Manga” is still overused as the umbrella concept

Many of the most central abstractions still carry manga-centric names even though the app is no longer manga-only in product reality.

### Why this matters

This can gradually distort future design, because contributors will tend to keep shoving new concepts into manga-shaped abstractions.

## 3. Repository factory complexity is growing

The source-to-repository path is becoming one of the densest pieces of architectural logic.

### Why this matters

Every future ecosystem addition increases the chance that this area becomes harder to reason about and test.

## 4. Mihon and Aniyomi duplication can spread

The current symmetry between manga-extension and anime-extension management is understandable, but duplication at the runtime and orchestration level should not grow unchecked.

## 5. OCR complexity can overtake reader-core clarity

The OCR pipeline is promising, but it is now advanced enough that it needs explicit subsystem boundaries to avoid becoming an architectural drag on the core reader.

## 6. The network and JavaScript compatibility zones are inherently high-risk

These parts of the system will naturally experience frequent breakage driven by external change. That means they need especially strong debug surfaces and isolation of responsibility.

## Most Valuable Next-Level Improvements

## 1. Formalize a content-platform layer

The app's true center is no longer “manga.” It is unified content access and consumption.

A future platform vocabulary could become clearer around ideas such as:

- content identity
- source resolution
- repository capability descriptors
- content kind differentiation
- shared consumption entry contracts

Even if existing classes are not renamed immediately, this mental model should guide future architecture.

## 2. Separate source resolution from repository instantiation

The current factory works, but future maintainability would improve if the pipeline were conceptually split into:

- source normalization / resolution
- repository selection
- instance caching

## 3. Define an explicit external extension platform model

Kototoro already has most of the pieces of an extension runtime platform. Naming and tightening that subsystem would help future work on trust, install flow, repo catalogs, and runtime integration.

## 4. Keep reader core separate from reader enhancements

A useful long-term split would be:

- reader core
- content rendering
- enhancement systems such as OCR, translation, overlays, and advanced analysis

## 5. Invest in observability for compatibility-heavy subsystems

The project will benefit from strong tracing or debug artifacts for:

- source resolution
- extension loading
- repository creation
- OCR pipeline stages
- sync triggering and execution
- network compatibility events

## 6. Add more system-view documentation

The current docs are already good for task-oriented guidance. The next step is more project-level system docs, for example:

- architecture overview
- content access architecture
- reader subsystem overview
- sync vs backup boundary explanation
- extension runtime overview

## Recommended Priority After the Manga-to-Content Rename

The broad naming migration from manga-centric generic classes to content-centric ones is already being handled separately. Excluding that work, the remaining improvements are best prioritized by architectural pressure, blast radius, debugging value, and dependency ordering.

### P0: Do first

#### 1. Separate source resolution from repository instantiation

This is the highest-value structural change because it sits directly on the main content-access path and is already showing signs of becoming a maintenance bottleneck.

Why this comes first:

- it is a high-traffic architectural chokepoint
- every new source ecosystem increases its complexity
- poor separation here makes future testing and debugging harder
- this refactor unlocks cleaner follow-up work in extension runtime and platform abstractions

Suggested scope:

- split source normalization and identity handling from repository selection
- split repository construction from instance caching
- preserve current behavior while reducing responsibility density

#### 2. Invest in observability for compatibility-heavy subsystems

Kototoro depends on several high-variance subsystems that fail due to external change rather than only internal regressions. That makes observability an immediate engineering multiplier.

Why this comes first:

- it reduces diagnosis cost across networking, OCR, sync, and extensions
- it lowers the risk of later refactors by making failures visible
- it improves contributor productivity without requiring a large architecture rewrite

Suggested scope:

- add structured logging around source resolution and repository creation
- add clearer extension loading diagnostics
- trace sync triggers and execution paths
- expose OCR stage failures and fallback decisions
- capture network compatibility events with actionable metadata

### P1: High priority

#### 3. Control Mihon and Aniyomi runtime duplication

The current symmetry is understandable, but duplicated orchestration logic will become expensive if it keeps spreading.

Why this is high priority:

- it is a clear DRY pressure point
- behavior drift between two similar ecosystems becomes likely over time
- future extension-management work will otherwise require double implementation

Suggested scope:

- identify shared runtime lifecycle concerns first
- extract common install, update, state, and loading flows where practical
- keep ecosystem-specific adapters where differences are real

#### 4. Keep reader core separate from reader enhancements

The OCR and translation pipeline is valuable, but it should remain an enhancement layer rather than becoming part of the correctness path of the reader itself.

Why this is high priority:

- reader stability is a core product requirement
- enhancement complexity should not dictate base reading architecture
- this boundary helps protect performance and maintainability

Suggested scope:

- define a reader core that remains complete without OCR or translation
- isolate enhancement hooks for overlays, OCR, translation, and analysis
- make fallback behavior explicit when enhancement stages fail

#### 5. Clarify the boundary between sync and backup

These systems are related but conceptually different. If the distinction stays blurry, both implementation and documentation will continue to accumulate ambiguity.

Why this is high priority:

- users and contributors can easily interpret multiple systems as one kind of sync
- unclear boundaries lead to confusing settings, triggers, and ownership
- this is a relatively cheap clarity win with broad product impact

Suggested scope:

- define system responsibilities and terminology explicitly
- document trigger models and expected outcomes separately
- align implementation boundaries with those definitions where needed

### P2: Medium priority

#### 6. Define an explicit external extension platform model

This is strategically important, but it should build on top of cleaner source and runtime boundaries rather than trying to hide current complexity behind a new abstraction too early.

Why this is medium priority:

- the direction is strong, but the foundation should be stabilized first
- premature platformization risks adding vocabulary without reducing complexity

Suggested scope:

- define the minimal model for repository, package, trust, install state, and runtime handle
- use that model to guide later UI and orchestration cleanup

#### 7. Add more system-view documentation

This is low-risk, relatively cheap, and useful throughout the rest of the roadmap, but it should follow or accompany real architecture clarification rather than substitute for it.

Why this is medium priority:

- it improves onboarding and design communication
- it helps preserve architectural intent during refactors
- it becomes more useful once core boundaries are made more explicit

Suggested first documents:

- content access architecture
- extension runtime overview
- reader subsystem overview
- sync vs backup boundary explanation

### P3: Long-term priority

#### 8. Formalize a content-platform layer

This is the right long-term mental model, but it should emerge from incremental architectural convergence rather than a large top-down framework effort.

Why this is later:

- the direction is already visible in the codebase and docs
- forcing a broad platform abstraction too early risks YAGNI and unnecessary indirection
- the best version of this abstraction will be clearer after the higher-pressure boundaries are cleaned up

Suggested scope:

- evolve platform vocabulary gradually
- introduce abstractions only when multiple subsystems already need them
- let source, reader, and extension refactors inform the eventual platform model

## Recommended Delivery Sequence

For practical execution, the most effective order is:

1. separate source resolution, repository selection, and instance caching
2. add observability around the compatibility-heavy paths
3. reduce Mihon and Aniyomi runtime duplication
4. tighten reader core versus reader enhancement boundaries
5. clarify sync versus backup as separate systems
6. formalize the extension platform model on top of the improved foundation
7. expand system-view documentation alongside the clarified architecture
8. continue converging toward a true content-platform model over time

For an execution-oriented version of this priority order, see [`architecture-roadmap.md`](./architecture-roadmap.md).

## Overall Assessment

Kototoro is already beyond the level of a simple reader fork. It is evolving into a unified Android content platform for manga, novels, video, OCR-enhanced reading, and external ecosystem compatibility.

That is a strong and differentiated direction.

The core challenge for the next stage is no longer “can this feature be added?”

It is:

> Can the project continue to grow while keeping its architectural center legible?

Right now, the answer is still yes.

The most important thing to protect is the quality of the core structural decisions already in place:

- source abstraction
- separate consumption systems for different content types
- serious local/offline support
- extension ecosystem integration as a first-class concern

If future work keeps reinforcing those foundations instead of only stacking more features on top, Kototoro can continue becoming much more capable without turning into an unmanageable giant.
