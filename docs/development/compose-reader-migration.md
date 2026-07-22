# Compose Reader Migration

## Goal

Replace the manga reader UI with Jetpack Compose without changing the parser,
page cache, chapter loading, translation, or reading-progress contracts.

The migration uses Compose Foundation `HorizontalPager` and `VerticalPager`.
Google does not provide a complete manga-reader sample, so the implementation
combines the official pager and gesture APIs behind Kototoro-owned interfaces.

## Boundaries

Keep these existing components:

- `ReaderViewModel` and `ReaderContent` as the reader-level state owner.
- `ChaptersLoader` and the network/cache portion of `PageLoader`.
- `ReaderPageEnhancementController` for translation and enhancement variants.
- `ReaderState` as the persisted reading-position contract.

Replace these UI components incrementally:

- `PagerReaderFragment`, `ReversedReaderFragment`, and `VerticalReaderFragment`.
- RecyclerView/ViewPager adapters and page holders.
- `SubsamplingScaleImageView` gesture and rendering integration.
- Reader toolbar, sheets, overlays, and touch-grid dispatch.

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
4. **Chrome and tools**
   - Compose top/bottom controls, chapter sheet, timer, bookmarks, save,
     OCR/translation controls, and accessibility semantics.
5. **Cutover**
   - Run old and Compose readers behind an internal switch.
   - Add UI tests and macrobenchmarks for every reader mode.
   - Remove Fragment, RecyclerView, ViewPager, and ViewBinding reader UI only
     after feature and performance parity is verified.

## Cutover gates

- Returning from reader preserves chapter, page, scroll, cover, and system bars.
- A 100+ page chapter does not retain decoded off-screen bitmaps.
- Pinch/pan never triggers a page turn until the image reaches its pan bound.
- Translation layer changes retain scale and center.
- Standard, reversed, vertical, webtoon, and double-page modes pass device tests.
- TalkBack exposes page position, loading, retry, and reader controls.

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
