# Compose Reader Migration

## Goal

Replace the manga reader UI with Jetpack Compose without changing the parser,
page cache, chapter loading, translation, or reading-progress contracts.

The same pure-Compose end state now also applies to the novel reader. Its detailed
behavioral gates are tracked in `compose-novel-reader-parity-matrix.md`; manga and
novel readers should share chrome, modal, message, and global Space infrastructure
where their behavior matches, while retaining separate image and text renderers.

The migration uses Compose Foundation `HorizontalPager` and `VerticalPager`.
Google does not provide a complete manga-reader sample, so the implementation
combines the official pager and gesture APIs behind Kototoro-owned interfaces.

## Boundaries

Keep these existing components:

- `ReaderViewModel` and `ReaderContent` as the reader-level state owner.
- `ChaptersLoader` and the network/cache portion of `PageLoader`.
- `ReaderPageEnhancementController` for translation and enhancement variants.
- `ReaderState` as the persisted reading-position contract.

Replace these UI components completely:

- `PagerReaderFragment`, `ReversedReaderFragment`, and `VerticalReaderFragment`.
- RecyclerView/ViewPager adapters and page holders.
- `SubsamplingScaleImageView` gesture and rendering integration.
- Reader toolbar, sheets, overlays, and touch-grid dispatch.

The final reader Activity must call Compose content directly. It must not inflate a
reader layout, host a `ComposeView` inside a View container, retain ViewBinding, or
use reader Fragments as UI/navigation adapters.

## Milestones

1. **Compose page foundation**
   - View-independent page loading state.
   - Horizontal, reversed, and vertical pagers.
   - Pinch, pan, and double-tap zoom.
   - Deterministic page keys and reading-position callbacks.
2. **Rendering parity**
   - Region decoding for very large images.
   - Preview, progress, retry, animated images, crop, and color filters.
   - Original/translated layer switching without resetting transforms.
   - Replace the temporary `ComposeReaderPageLoader` bridge with the
     Compose-owned image pipeline.
3. **Mode parity**
   - Webtoon continuous list and chapter-boundary loading.
   - Double-page layout, wide-page detection, RTL, and foldables.
   - Auto-scroll and hardware-key navigation.

4. **Pure Compose Activity and tools**
   - Compose top/bottom controls, chapter sheet, timer, bookmarks, save,
     OCR/translation controls, and accessibility semantics.
   - Move tap-grid dispatch, transient messages, loading, insets, and system-bar
     state into the Compose root.
   - Replace `ReaderManager`/`ComposeReaderController.view` hosting with direct
     Activity Compose content.
5. **Cutover**
   - Add UI tests and macrobenchmarks for every reader mode.
   - Delete manga-reader Fragment, RecyclerView, ViewPager, ViewBinding, custom
     reader View, and XML layout implementations after their Compose consumers
     are connected.
   - Verify no manga-reader runtime path inflates XML or adds a ComposeView to a
     ViewGroup.

### Implemented mode-parity details

- Webtoon rendering preserves the visible list anchor when image dimensions resolve,
  caps very tall items to the viewport, and stores their internal scroll offset by
  stable page key.
- Webtoon gap, zoom, default zoom-out, and pull-to-switch-chapter preferences have
  direct Compose consumers. Pull feedback uses the legacy 30% viewport threshold
  and delegates chapter changes to `ReaderViewModel`.
- The Compose reader surface observes pointer events without consuming pager or zoom
  gestures, resolves the nine-area tap grid, suppresses single-tap actions during a
  double tap, and delegates short/long `TapAction` mappings to `ReaderControlDelegate`.
- Reader interaction now notifies `ScrollTimer` directly; the Activity no longer
  overrides `dispatchTouchEvent` or depends on `TapGridDispatcher`/`MotionEvent`.
- Legacy manga page adapters, holders, layout managers, page ViewModels, custom
  pager/webtoon Views, and their item XML layouts have been removed. The shared
  `ReaderPage`, `ReaderUiState`, and image/background algorithms remain.
- `ReaderManager` has been removed; reader mode, double-page state, navigation, and
  persisted position are now delegated directly to `ComposeReaderController`.
- Compose chrome is now the active manga-reader chrome. Loading, info bar, messages,
  zoom controls, actions, control visibility, translation state, and auto-scroll
  panel are no longer mirrored into legacy reader Views.
- `ReaderActivity` now extends `BaseComposeActivity` and sets Compose content
  directly. `ComposeReaderController` exposes composable content instead of owning
  a `ComposeView`; all `activity_reader.xml` variants and ViewBinding are removed.
- Transient notifications and page-save results now use the Compose message host.
  Its optional action preserves single-page sharing without a Snackbar/View anchor.
- The reader embeds the global `SpaceSwitcherDelegate.Fab` directly in its Compose
  tree. Visibility, active-space state, availability, switching, session restore,
  sheet, and transition behavior remain owned by the shared Space infrastructure;
  no reader-specific Space implementation is introduced.
- The toolbar options command now opens a Compose `ModalBottomSheet`. Reader mode,
  landscape/foldable double-page preferences, split pages, sensitivity,
  super-resolution, background, save, bookmark, rotation, auto-scroll, translation,
  color-filter, browser, translation diagnostics, and the full settings command are
  connected to the existing Activity/settings contracts. Sections use adaptive rows
  and wrapped action groups instead of a single narrow control column.
- Preferred image-server selection now exposes repository-backed state and mutation
  APIs to Compose. The Compose sheet renders the available servers and preserves the
  existing parser configuration and cache-invalidation behavior.
- The legacy manga `ReaderConfigSheet`, its XML layout, ViewBinding, and router entry
  have been removed. Translation task logs now open a Compose-owned sheet; the old
  Fragment, RecyclerView adapter, ViewBinding, and XML implementation have been
  removed. Chapters, pages, and bookmarks now use the shared Compose tabs content in
  both details and reader; the old Fragment/XML/`ComposeView` host and compatibility
  router APIs have been removed.
- The novel reader settings route is now a state-hoisted Material3 Compose sheet in
  the existing novel Compose root. It uses adaptive sections for reading mode, theme,
  typography, margins, status controls, translation display, and reader actions; the
  legacy `NovelReaderConfigSheet` and its XML layout have been removed.
- The novel chapter selector now uses a Compose `ModalBottomSheet` with stable list
  keys, current-chapter positioning, title/branch/scanlator search, reversible order,
  grouped headings, and a constrained wide-screen content width. Its Fragment,
  RecyclerView adapter, ViewBinding, and chapter item XML files have been removed.
- Novel loading and transient messages now use a ViewModel-driven Compose overlay.
  Message identity prevents an older timeout from dismissing a newer notification;
  the legacy loading group and `ReaderToastView` have been removed from novel XML.
- Novel TTS playback state and controls now use the same Compose overlay boundary.
  Play/pause, previous/next token, voice selection, and stop actions still delegate
  to `TtsService`; the legacy TTS button row has been removed from novel XML.

## Cutover gates

- Returning from reader preserves chapter, page, scroll, cover, and system bars.
- A 100+ page chapter does not retain decoded off-screen bitmaps.
- Pinch/pan never triggers a page turn until the image reaches its pan bound.
- Translation layer changes retain scale and center.
- Standard, reversed, vertical, webtoon, and double-page modes pass device tests.
- TalkBack exposes page position, loading, retry, and reader controls.
- No manga page renderer depends on RecyclerView, ViewPager, ViewBinding, or an XML
  item layout.

## Compose image pipeline

The Compose reader must not call `ReaderSuperResolutionManager`. That class is
retained only for the legacy reader and download worker during migration.

The new pipeline is lifecycle-scoped and emits progressive display states:

```text
LoadingOriginal
    -> OriginalReady
    -> Enhancing(original remains visible)
    -> EnhancedReady
```

An enhancement failure keeps the original image visible. Leaving a page cancels
work that is not shared by another visible/prefetched page. Download/cache keys
belong to the original image; enhancement cache keys additionally include the
engine, model, scale, noise level, and source fingerprint.

Responsibilities are separated as follows:

- Original image fetcher: repository request, headers, disk cache, archive URI.
- Decode metadata probe: dimensions, format, animation, and memory estimate.
- Compose image enhancer: cancellable super-resolution processing and cache.
- Display variant resolver: original, enhanced, translated, or translated-enhanced.
- Compose page state holder: collects the pipeline only while its page is active.

This avoids inheriting the legacy manager's global engine lifecycle, `GlobalScope`
cleanup, and download/enhancement task-key coupling.
