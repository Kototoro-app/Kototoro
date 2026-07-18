# Quality Guidelines

> Code quality standards for frontend development.

---

## Overview

<!--
Document your project's quality standards here.

Questions to answer:
- What patterns are forbidden?
- What linting rules do you enforce?
- What are your testing requirements?
- What code review standards apply?
-->

The project does not run Gradle lint by default: Release lint analysis is expensive, while UI changes are validated through resource processing, Kotlin compilation, tests, and diff checks. Skip `:app:lint*` tasks unless the user explicitly requests lint.

---

## Forbidden Patterns

<!-- Patterns that should never be used and why -->

Do not introduce unrelated refactors or suppressions merely to satisfy lint. Fix compilation errors, resource errors, and behavioral regressions directly.

---

## Required Patterns

<!-- Patterns that must always be used -->

For code changes, run the relevant Gradle resource-processing or Kotlin-compilation task and `git diff --check`; run applicable tests when they exist. Do not treat lint as a default quality gate.

---

## Testing Requirements

<!-- What level of testing is expected -->

Review requirement coverage, compilation results, resource completeness, test results, and the working-tree diff; add lint only when explicitly requested by the user.

---

## Code Review Checklist

<!-- What reviewers should check -->

(To be filled by the team)

## Scenario: Backdrop Menus and Popup Coordinates

Backdrop layer sampling is coordinate-dependent. Android Compose `DropdownMenu`
uses a separate Popup window, so passing a root `LayerBackdrop` into that Popup
can sample the wrong location, commonly the screen origin.

### Required pattern

- Menus that must refract the content beneath them are rendered in a root-level
  Compose overlay, above the content layer and below no later chrome.
- The trigger reports `boundsInRoot()` to the overlay host; the overlay measures
  its actual content width and height before aligning it to the trigger.
- Root-overlay requests declare whether the menu opens above or below the
  anchor. Bottom chrome actions open above; top chrome actions keep the default
  downward placement. Clamp the measured menu bounds inside the root window.
- Use the shared root backdrop and keep the official effect order:
  `vibrancy()`, `blur(...)`, then `lens(...)`.
- A Popup menu must use a static opaque glass surface with runtime sampling
  disabled; never create a new `LayerBackdrop` inside the Popup.
- Outside-tap dismissal must update the owner menu state, not only hide the
  overlay visually.

### Validation

- Verify the menu samples the content immediately beneath its rounded bounds,
  not the top-left corner of the window.
- Verify the trailing edge remains aligned after the menu's natural width is
  measured and after rotation or window-size changes.
- Verify bottom-anchored menus use `anchor.top - menu.height - gap`, while
  top-anchored menus keep `anchor.bottom + gap`; both paths remain inside root
  bounds when the menu is larger than the available space.
- Run `:app:compileDebugKotlin` and `git diff --check`; do not run Gradle lint
  unless explicitly requested.

## Scenario: Backdrop Source and Effect Isolation

`LayerBackdrop` is coordinate-dependent and records the content of its
`Modifier.layerBackdrop` node. A source node must not contain a consumer that
draws the same backdrop.

### Required pattern

- Create and provide one shared `LayerBackdrop` at the screen owner.
- Attach `Modifier.layerBackdrop` only to the background/content layer that is
  safe to record.
- Render `drawBackdrop` glass controls as later siblings or overlays outside
  that source subtree.
- Keep `exportedBackdrop` on glass-on-glass components; it does not make it safe
  to wrap the consumer in the root source.
- When Haze and Backdrop support the same screen, prefer the same source-layer
  boundary for `hazeSource` and `layerBackdrop`.

| Condition | Required behavior |
|---|---|
| Backdrop is available and iOS style is active | Register the pure background/content source |
| Backdrop is absent or another style is active | Skip `layerBackdrop` and use the existing fallback |
| A consumer is inside the proposed source subtree | Move the source boundary; do not create another nested backdrop |
| A Popup/Dialog uses another window | Use the static fallback unless a same-window overlay host exists |

#### Wrong

```kotlin
Box(Modifier.layerBackdrop(backdrop)) {
    ScreenContent()
    GlassChrome(Modifier.drawBackdrop(backdrop, /* ... */))
}
```

#### Correct

```kotlin
Box {
    ScreenContent(Modifier.layerBackdrop(backdrop))
    GlassChrome(Modifier.drawBackdrop(backdrop, /* ... */))
}
```

Validation must confirm that the source has non-zero bounds, precedes the
consumer in draw order, excludes every consumer of the same backdrop, and
continues to compile and fall back safely without the CompositionLocal source.

## Scenario: iOS Interface Style Effect Isolation

The iOS interface style uses the Backdrop/Liquid Glass pipeline. Haze is a
separate visual-effects backend and must never become an implicit fallback for
an iOS-styled component.

### Contract

- `isRuntimeHazeAvailable()` owns the shared style-and-platform guard. Every
  `hazeSource` and `GlassSurface`/`hazeEffect` eligibility check must use it;
  callers must not remember their own iOS exclusion.
- Use Backdrop only when the consumer can safely sample a same-window source.
- A Popup/Dialog/`ModalBottomSheet` without a same-window overlay host uses the
  static translucent `Surface` fallback in iOS style.
- Non-iOS styles continue to combine glass preferences, the caller opt-in, and
  platform capability when deciding whether to use Haze.

```kotlin
@Composable
internal fun isRuntimeHazeAvailable(): Boolean =
    LocalInterfaceStyle.current != InterfaceStyle.IOS && supportsRuntimeHaze()

val useRuntimeHaze = glassPrefs.isGlassEffectEnabled &&
    allowRuntimeHaze &&
    isRuntimeHazeAvailable()
```

| Condition | Required behavior |
|---|---|
| iOS style, safe same-window Backdrop | Use Backdrop/Liquid Glass |
| iOS style, Backdrop absent or cross-window | Use a static translucent surface; never Haze |
| Non-iOS style, Haze enabled and supported | Preserve the existing Haze path |
| Any style, runtime effects disabled | Render the existing static fallback |

- Good: enforce the style exclusion once in `isRuntimeHazeAvailable()`, reuse
  it for all Haze sources/effects, and use a dedicated Backdrop component where
  sampling is safe.
- Base: an iOS `ModalBottomSheet` remains a static rounded glass surface.
- Bad: assume that omitting `hazeSource` at the screen root prevents every
  nested `hazeEffect`, or pass a root `LayerBackdrop` into another window.

Validation must cover `:app:compileDebugKotlin`, `git diff --check`, the iOS
`useRuntimeHaze=false` branch, and unchanged non-iOS Haze eligibility.

## Scenario: Backdrop Control Content Colors

`Box` does not establish a Material content color. A Backdrop-based control
that replaces `Surface` must therefore provide its own theme-aware content
color, otherwise icons and text can retain a black default in dark mode.

### Required pattern

- Wrap Backdrop control content with
  `CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface)`.
- Keep deliberate child-level colors, such as `onSurfaceVariant`, as explicit
  overrides.
- Apply the same rule to root-overlay menus because they do not inherit a
  `Surface` content color from their visual background modifier.

Validation must cover light and dark themes for icons, labels, and menu rows in
both the Backdrop path and the static fallback path.

## Scenario: Immersive Top Scrim Visibility

The top immersive gradient protects content under system bars and floating
chrome. Content scroll and chrome visibility are independent signals: during a
reverse scroll, one can decrease before the other becomes fully visible.

### Contract

Use the stronger signal and clamp the result:

```kotlin
alpha = maxOf(contentScrollAlpha, chromeAlpha).coerceIn(0f, 1f)
```

Do not derive the gradient only from forward-scroll collapse. Validate the
initial state, fully collapsed state, and reverse-scroll interval where chrome
has reappeared but the content-derived alpha is falling.

## Scenario: Global Space Switcher Ownership

The Space switcher FAB is a global navigation control. It must remain available
on main-shell, content-list, search, and details destinations when the setting
is enabled, subject to existing route-specific obstruction rules.

### Required pattern

- Keep one shared FAB renderer and mount it in mutually exclusive hosts:
  main-shell chrome for normal destinations, and the root overlay for routes
  that suppress main-shell chrome.
- Preserve the existing details bottom-panel avoidance and Space-switcher
  enablement conditions.
- Use the root anchored menu only when the main-shell Backdrop overlay host is
  available. Routes without that host open the existing unanchored sheet
  fallback; do not pass root Backdrop coordinates across windows.
- Do not replace the global FAB with route-specific top-bar buttons.

Validation must confirm one FAB and one switcher host per destination, correct
bottom-right positioning on immersive routes, and no duplicate overlay when
returning to the main shell.
