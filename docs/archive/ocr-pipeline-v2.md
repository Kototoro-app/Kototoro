# OCR Pipeline

> Archived: this target-design document predates the completed OCR and translation refactor. It is retained for historical context and is not the current implementation reference.

## Goal

This document defines the target OCR architecture Kototoro should preserve and continue refining on this branch.

It is not a proposal to go back to a bubble-first OCR design.

The intended core rule is:

```text
OCR core path = detection -> recognition -> merge
bubble logic = post-OCR helper only
```

The current implementation review is documented in [OCR Architecture Review](./ocr-architecture-review.md).

## Design Principles

## 1. Text geometry is authoritative

For manga OCR, text regions are more reliable than guessed bubble regions.

The detector stage should define OCR geometry.

That means:

- OCR starts from text regions, not bubble regions
- recognizers receive detector regions
- bubble logic must not decide whether OCR runs

## 2. Detection and recognition must remain separate responsibilities

Detector responsibilities:

- find text regions
- output page-space geometry
- provide rects or quads

Recognizer responsibilities:

- crop or warp detector regions
- decode text
- return recognized blocks

This separation is already partially implemented through `ReaderTextDetector` and dedicated region recognizers. It should remain the primary architecture boundary.

## 3. Merge owns text-structure rebuilding

Merge should be the stage that reconstructs readable text units from recognized fragments.

It should handle:

- reading order
- vertical text composition
- overlap deduplication
- block reconstruction

Bubble grouping may provide hints, but merge should remain the authoritative structure-building stage.

## 4. Bubble logic is advisory

Bubble detection may still be useful for:

- grouping hints
- render anchor refinement
- optional fallback behavior for niche cases

But it must not become the default OCR entrance again.

## Target Runtime Shape

The target runtime shape is:

```text
page image
  -> text detection
  -> region recognition
  -> merge
  -> optional bubble-assisted grouping
  -> translation
  -> render
```

This is already close to the active branch and should be treated as the stable direction.

## Stage 1. Text Detection

Input:

- full page bitmap

Output:

- `List<TextRegion>`

Required properties:

- page-space bounding rect
- confidence
- optional quad points
- optional direction / angle hints
- detector identity for diagnostics

Current implementations already include:

- ML Kit derived region extraction
- Paddle detector
- CTD detector with ONNX Runtime and quad output

Target rule:

- detector output is authoritative OCR geometry
- no brightness-based bubble gate should block this stage

## Stage 2. Region Recognition

Input:

- page bitmap
- detector regions

Output:

- `List<OcrTextBlock>`

Rules:

- each region is cropped or warped independently
- recognizers must not redefine page search space
- recognizers should be swappable without rewriting the detector stage

Current recognizer backends already include:

- ML Kit
- Paddle
- MangaOCR

The important invariant is not which recognizer is used. The invariant is that recognition consumes detector regions.

## Stage 3. Merge

Input:

- recognized text blocks

Output:

- merged translation units

Responsibilities:

- combine related fragments
- preserve vertical reading where needed
- deduplicate overlapping text
- keep narration and SFX representable

This stage should continue to absorb logic that previously lived in bubble-centric heuristics.

## Stage 4. Optional Bubble Assistance

Input:

- merged text groups
- optional bubble detector output

Output:

- groups with optional bubble anchors or render hints

Rules:

- bubble assistance must be optional
- OCR should still succeed when bubble detection is disabled or inaccurate
- bubble geometry may refine render placement, but must not redefine OCR geometry

This stage can legitimately remain in Kototoro because the reader overlay renderer benefits from bubble-aware placement.

## Stage 5. Translation

Input:

- stable merged source groups

Output:

- translated groups

Translation should operate on merged text units, not on raw OCR fragments and not on bubble ROIs.

## Stage 6. Render

Input:

- translated groups
- render anchors

Output:

- page overlay

Target rules:

- render should consume final merged groups
- render should not influence OCR routing
- quad-aware crop quality should be preserved as far downstream as practical

The current branch already exposes render diagnostics. That should be preserved as a first-class debugging aid.

## What Must Not Return

## 1. Bubble-first default OCR

This must not become the default path again:

```text
bubble detector -> ROI OCR
```

If a bubble-first route remains, it should stay explicitly secondary and strategy-dependent.

## 2. Brightness heuristics as OCR gate

Speech-bubble luminance checks are too brittle for manga.

If they remain, they must stay downstream and advisory only.

## 3. Monolithic OCR engine thinking

The system should not collapse back into "one engine does everything".

The architecture should remain explicit about:

- detector backend
- recognizer backend
- merge stage

That is clearer, more testable, and easier to extend.

## 4. Detector-specific logic hidden inside unrelated engines

CTD should stay a standalone detector.

Future manga-oriented detectors should also be integrated through detector contracts rather than mounted as special cases inside Paddle or other recognizers.

## Minimal Interface Direction

The codebase is already close to these boundaries:

```kotlin
interface ReaderTextDetector {
    suspend fun detect(sourceUri: Uri): List<TextRegion>
    suspend fun detect(bitmap: Bitmap): List<TextRegion>
}
```

```kotlin
interface ReaderTextRecognizer {
    suspend fun recognize(sourceUri: Uri, regions: List<TextRegion>): List<OcrTextBlock>
}
```

The architectural requirement is:

- page OCR orchestration should be expressed in terms of these stages
- route resolution should choose detector and recognizer pairings explicitly

## Debugging Requirements

The branch now has a useful render-debug mechanism. The target architecture should preserve and expand this kind of observability.

Useful runtime signals include:

- detected region count by backend
- recognized block count by route
- merge count before and after grouping
- selected route key
- render diagnosis per prepared bubble

This is important because the remaining failures are now mostly quality and geometry issues, not "did OCR run at all" issues.

## Near-Term Roadmap

The next architecture-preserving improvements should be:

1. Keep CTD, Paddle, and ML Kit integrations expressed as detector/recognizer combinations rather than ad hoc engine branches.
2. Continue shrinking the role of bubble-first fallback.
3. Push quad-aware geometry further into render placement.
4. Keep tuning render sizing based on the new overlay diagnosis instead of adding more heuristics blindly.

## Summary

Kototoro should now be developed under this assumption:

```text
The branch has already crossed the architectural boundary.
The remaining work is refinement, not a return to the old OCR shape.
```

The correct long-term shape is still:

```text
detection -> recognition -> merge -> translation -> render
```

with bubble logic kept downstream and non-authoritative.
