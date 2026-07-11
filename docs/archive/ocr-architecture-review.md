# OCR Architecture Review

> Archived: this branch-specific implementation review predates the completed OCR and translation refactor. It is retained for historical context and is not the current implementation reference.

## Scope

This document describes the OCR pipeline that is currently implemented in Kototoro on this branch.

It focuses on:

- the active runtime architecture
- what has already been corrected
- which quality issues still remain
- how the current branch differs from the stricter target documented in [OCR Pipeline](./ocr-pipeline-v2.md)

This document intentionally describes the code that exists now, not an aspirational design.

## Executive Summary

The OCR stack is no longer bubble-first.

The active branch now follows a detector-first, recognizer-second pipeline, with bubble logic demoted to optional post-OCR assistance:

```text
page image
  -> text detection
  -> region recognition
  -> text merge
  -> optional bubble-assisted grouping
  -> translation
  -> render
```

The main branch-level improvements are:

1. `ComicTextDetectorOnnx` is now a standalone detector implementation instead of being mounted under the Paddle engine.
2. OCR routing in `ReaderPageTranslationProcessor` now explicitly models detector and recognizer backends.
3. CTD is a first-class `OCR_DETECTOR` model and can be paired with `MangaOCR` or Paddle recognition.
4. Bubble detection is no longer the default OCR entrance. It is retained only as an optional downstream assistant.
5. Render sizing for detector-anchored groups has been widened, and the reader now exposes a render debug overlay with explicit diagnoses.

The current architecture is much closer to `manga-translator-ui` than the earlier bubble-ROI-centric design, but it is still not identical.

## Current Runtime Behavior

## OCR routing is now detector/recognizer-based

The real runtime behavior is no longer well described by a single "OCR engine" label.

At the page level, `ReaderPageTranslationProcessor` now resolves a `PageOcrRoute` with:

- detector backend:
  - `MLKIT`
  - `PADDLE`
  - `CTD`
  - `BUBBLE_DETECTOR` as an optional bubble-first Japanese fallback path
- recognizer backend:
  - `MLKIT`
  - `PADDLE`
  - `MANGA_OCR`

This is the most important architecture correction on the branch. The pipeline is now expressed in terms of:

```text
detector -> recognizer
```

instead of a monolithic OCR engine abstraction.

## CTD is now a real standalone detector

[ComicTextDetectorOnnx.kt](../../app/src/main/kotlin/org/skepsun/kototoro/reader/translate/domain/ComicTextDetectorOnnx.kt) is now a dedicated `ReaderTextDetector`.

It is no longer treated as a Paddle detector variant.

Its current behavior is:

- ONNX Runtime inference
- `1024x1024` letterbox preprocessing
- primary decoding from the `det` score map
- secondary recovery from `seg`
- fallback region recovery from `blk`
- quad generation for rotated regions

That means CTD now contributes detector geometry directly, including non-axis-aligned quads that can later be used for crop warping.

## Supported active page OCR routes

The current active routes in [ReaderPageTranslationProcessor.kt](../../app/src/main/kotlin/org/skepsun/kototoro/reader/translate/domain/ReaderPageTranslationProcessor.kt) include:

- `MLKIT -> MLKIT`
- `MLKIT -> MANGA_OCR`
- `MLKIT -> PADDLE`
- `PADDLE -> PADDLE`
- `PADDLE -> MANGA_OCR`
- `CTD -> PADDLE`
- `CTD -> MANGA_OCR`

There is also an optional Japanese-only `BUBBLE_DETECTOR -> MANGA_OCR` path when the configured pipeline strategy prefers bubble-first behavior.

This bubble-detector route still exists, but it is no longer the default architecture and should be treated as an escape hatch rather than the primary manga OCR path.

## Merge and grouping are downstream from OCR

The active reader flow now behaves like:

1. Detect text regions.
2. Recognize text from those regions.
3. Merge fragments into translation units.
4. Optionally use bubble detection to attach groups to bubbles.
5. Translate and render.

This is a material improvement over the old "bubble detector decides the OCR search space" design.

## Rendering diagnostics now exist in the runtime

The branch now contains a dedicated render-debug layer:

- `BubbleDebugOverlay` in [ReaderTranslationBubbleModels.kt](../../app/src/main/kotlin/org/skepsun/kototoro/reader/translate/domain/ReaderTranslationBubbleModels.kt)
- debug drawing and diagnosis in [ReaderPageTranslationProcessor.kt](../../app/src/main/kotlin/org/skepsun/kototoro/reader/translate/domain/ReaderPageTranslationProcessor.kt)

When translation debug logging is enabled, the runtime can now visualize:

- source rect
- source content rect
- prepared render rect
- final content area

It also emits a diagnosis string:

- `渲染框偏小`
- `内容区偏小`
- `渲染框偏大`
- `基本匹配`
- `无内容框`

This has turned render debugging from guesswork into an inspectable runtime feature.

## What Has Been Corrected

## 1. CTD is no longer mounted under Paddle

Earlier versions mixed CTD into the Paddle engine path, which violated separation of concerns.

That is now fixed:

- Paddle handles Paddle detection and recognition.
- CTD handles CTD detection.
- recognizers consume detector regions through shared contracts.

This is a clear SRP improvement.

## 2. OCR model taxonomy is more truthful

`comic_text_detector_onnx` is now registered as `OCR_DETECTOR`, not as bubble detection.

That means model selection and runtime routing now agree with the actual role of the model.

## 3. Detector-anchored render rects are less aggressively compressed

The render path used to produce regions that were visibly too small even when OCR text and translation were complete.

The main correction in `prepareTranslatedBubble(...)` is:

- merge detector rect with `sourceContentRect` when available
- widen detector-anchored stabilization
- allow larger detector-anchored expansion scales

This corrected a real rendering bug rather than an OCR bug.

## 4. Render fitting is now a unified solver instead of layered fallback heuristics

The recent reader-side changes moved render preparation toward a more explicit fit model:

- single-box bubbles now go through a dedicated solver
- horizontal and vertical layout each produce measured content usage
- prepared rect, content width, and content height are resolved in one pass
- text drawing is aligned to measured layout bounds instead of approximate centering

This is important because the dominant failures on the branch are no longer:

- OCR missed the text
- translation dropped the text

They are more often:

- the chosen render box was too optimistic
- text was measured against one width but drawn with another origin
- a merged group produced geometry that was acceptable for translation but poor for overlay

## 5. Multi-rect rendering is now conservative instead of naive

The branch now carries per-group `sourceContentRects`, not only a single merged content rect.

That enables a pseudo-unmerge style render path:

- keep the merged text unit for translation
- project original OCR rects back into the final render area
- attempt segmented text flow across those projected regions
- reject segmented layouts when the projected sub-rects overlap too heavily

This is not true semantic redistribution of translated text back to original fragments. It is a conservative geometry strategy designed to avoid the worst outcome of treating an irregular merged region as one clean rectangle.

## 6. Overlapping sparse bubbles are filtered before draw

The runtime now suppresses a subset of highly overlapping bubbles when one candidate has:

- a much larger background footprint
- very low content fill ratio
- content that is largely contained by a denser neighbor

This specifically targets bad overlays such as:

- page numbers
- tiny decorative text
- false positive bubbles that produce a huge white box around a few characters

It is deliberately narrow. This is not a full duplicate-removal system.

## 4. UI naming is more user-facing

The translation settings and model-management pages have been renamed to reflect user intent:

- text detection / recognition
- local translation
- online translation
- bubble detection

instead of exposing too many `Paddle` / `ONNX` implementation names at the top level.

This does not change the core architecture, but it makes the runtime model more truthful for users.

## What Still Limits Quality

## 1. Bubble-first fallback still exists

`BUBBLE_DETECTOR -> MANGA_OCR` is still present as a strategy-dependent route.

That means the codebase has not fully committed to the rule:

```text
OCR core path = detector -> recognizer -> merge
```

as the only runtime architecture.

The branch is much closer to that rule now, but not completely exclusive yet.

## 2. Brightness-based speech-bubble heuristics still exist

`isLikelySpeechBubbleRegion(...)` still uses luminance thresholds.

This is no longer the dominant OCR gate, which is good, but it still affects downstream bubble-like rendering behavior.

That heuristic remains brittle for:

- dark bubbles
- colored bubbles
- dense screentones
- narration boxes
- text outside classic white speech bubbles

## 3. Render still relies on rectangle-centric sizing

CTD now provides quads, and recognizers can warp from quads, but the final render-preparation stage still works mostly with rectangles.

This means the branch has improved:

- crop quality
- region alignment

more than it has improved:

- final translated overlay geometry

For vertical or rotated dialogue, this is still a visible limitation.

## 4. Merge quality still dominates render quality

The recent render fixes improved fitting, centering, local expansion, and segmented flow.

They do not eliminate the core dependency on grouping quality.

If unrelated OCR fragments are merged together, the renderer still has to solve the wrong problem:

- the translation unit is semantically mixed
- the merged geometry is irregular
- segmented projection becomes unstable
- short residual clipping or oversized local regions can still appear

This means the branch has shifted from "render is obviously broken" to "render quality is now strongly bounded by merge quality."

## 5. Merge and bubble assignment are still tightly coupled in some downstream behavior

The branch has already demoted bubble logic relative to OCR, but bubble grouping still influences how merged units are anchored for rendering.

That is acceptable for now, but it means merge is not yet fully independent from downstream bubble-placement assumptions.

## Comparison With manga-translator-ui

The closest shared architectural shape is now:

```text
page image
  -> text detection
  -> text-region recognition
  -> merge
  -> translation
  -> render
```

Kototoro is now aligned with `manga-translator-ui` on these core principles:

- text detection is a first-class stage
- recognition consumes detector regions
- merge happens after recognition
- bubble logic is not the default OCR gate

The main remaining gap is not the detector/recognizer split anymore. The main remaining gap is that Kototoro still preserves more downstream bubble-aware rendering and optional bubble-first fallback behavior than `manga-translator-ui`.

## Current Architectural Assessment

The branch is no longer in the "wrong architecture" state.

The current state is better described as:

- the core OCR direction is correct
- the detector/recognizer split is implemented
- CTD has been promoted to a real detector
- render sizing and bubble-assisted downstream behavior still need refinement

In other words:

```text
Main risk has moved from OCR entrance design
to detector quality, merge quality, and render geometry quality.
```

## Recommended Next Focus

The highest-value next steps are:

1. Keep `detector -> recognizer -> merge` as the default and preferred route for all mainstream cases.
2. Continue reducing the architectural importance of bubble-first fallback.
3. Add stricter duplicate / high-overlap group suppression before render, not only sparse-overlay suppression after preparation.
4. Push quad-aware geometry further into render preparation, not just crop preparation.
5. Use the new debug overlay and diagnosis output to tune render sizing with real page evidence instead of heuristics alone.

The stricter target design is documented in [OCR Pipeline](./ocr-pipeline-v2.md).
