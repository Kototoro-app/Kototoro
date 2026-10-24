# TV reader layout: continuous reading fills the viewport width

## Symptom

On a television the manga reader rendered the chapter as a handful of narrow columns hugging the
left edge of the screen, with the rest of the 16:9 panel black. The audited book (`TV Audit Strip`,
4 pages of 960x7200) showed **4 columns of 144 px** covering **576 px of 1920** - 30 % of the width -
and no gaps between them.

## What the geometry actually does

The book was saved in `CONTINUOUS_HORIZONTAL` (mode 5), not in a vertical mode. That mode lays the
chapter out as a filmstrip along the reading direction and sizes every page from the viewport
*height*:

```
pageWidthPx = availableHeight * (pageWidth / pageHeight)
            = 1080 * (960 / 7200) = 144 px
```

`HorizontalReaderScene` forces each page's bounds height to `availableHeight`
(`bounds = FloatRect.fromLtwh(currentX, 0f, pageWidth, availableHeight)`), so the pages are never
scaled down and never drawn at their intrinsic size - the filmstrip is a fit-to-height layout by
design. On a phone viewport, which is taller than it is wide, that is what the mode promises. On a
television viewport it collapses tall pages into slivers, and because each page is only 144 px wide
four of them fit inside a third of the screen.

Switching the same book to **Webtoon** rendered the page across the full 1920 px immediately, which
proves the image pipeline, the bitmap decoding and the vertical scene were all correct: the defect
was the axis the continuous mode chose for a landscape viewport.

## Decision

Continuous reading on a television is laid out as **one full-width vertical column**, i.e. the
`VerticalReaderScene` path where `pageWidthPx == viewportWidth`. The saving and the user's choice
are untouched; only the composed layout changes, through one pure policy function:

- `app/src/main/kotlin/org/skepsun/kototoro/reader/ui/config/SceneLayoutMode.kt` -
  `resolveSceneLayoutMode(mode, isTv)` maps `CONTINUOUS_HORIZONTAL` to `WEBTOON` when
  `LocalUiPresentationConfig.current.isTv`, and returns every other mode unchanged on every
  presentation.
- `ComposeReaderScreenRoot` keys and dispatches the scenes on that resolved mode, so the whole
  layout - not just one branch - follows the policy.

Deliberately **not** changed:

- **Paged modes** (`STANDARD`, `REVERSED`, `VERTICAL`) still letterbox to the viewport height. A
  paged page that filled the viewport width would put its lower half out of reach: the TV key policy
  scrolls continuous scenes vertically with DPAD up/down and only turns pages in paged ones, so
  there is no gesture left to pan a widened page.
- **Phones and tablets** keep the filmstrip. `isTv` is false there, so the policy is the identity.

## Verification

- JVM: `SceneLayoutModeTest` (5 cases) - phone keeps the filmstrip, television maps it to the
  vertical column, webtoon is unchanged on both, paged modes are unchanged on a television, an
  unresolved mode stays unresolved. `:app:testDebugUnitTest --tests
  "org.skepsun.kototoro.reader.ui.config.SceneLayoutModeTest"` passes.
- Device (`sdk_google_atv64_x86_64`, 1920x1080, 960x540 dp): `TV Audit Strip` with saved
  `preferences.mode = 5` now renders one column whose page spans the full frame - the page's own
  white frame is measured at x = 31..1888, i.e. the source image's internal margin scaled to
  1920 px - instead of 4x144 px. DPAD down scrolls the column and DPAD right advances by a
  viewport-sized page turn, both without chrome.

## Known cosmetic gaps

- The options sheet still lists "Continuous horizontal" as the selected mode while the television
  renders the vertical column. The saved preference is genuinely still continuous horizontal; the
  layout policy is what differs.
- `isContinuousHorizontalReversed` has no meaning in the coerced column, so a reversed continuous
  preference is ignored on television.
