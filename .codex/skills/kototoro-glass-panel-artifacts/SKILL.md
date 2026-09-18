---
name: kototoro-glass-panel-artifacts
description: Diagnose and fix Kototoro Compose Material3 sheet, dialog and popup glass-panel artifacts — an extra light or gray rectangle, a pale plate, rectangular bounds around rounded glass panels, or a background that appears to grow while dragging the display options sheet — by finding which stacked layer draws it.
---

# Kototoro Glass Panel Artifacts

For glass panels inside a Material sheet, dialog or popup. Chrome — top-bar pills, the search button, bottom nav — has its own notes in `kototoro-artwork-chrome-artifacts`, and the liquid path belongs to `backdrop`.

## Core Lesson

Do not assume the glass layer is broken. These artifacts come from stacked drawing layers:

- Material3 `ModalBottomSheet`, `Dialog`, `DropdownMenu`, `Surface` or `Card` default container and elevation.
- Two layers tinting one area: the glass container colour plus a same-shape Material `Surface` colour.
- Shape mismatch: a rectangular elevation or tint layer behind rounded content.
- A `Surface` shadow reading through a translucent fill. `kototoro-artwork-chrome-artifacts` owns the general form of that rule.
- `GlassSurface(dialogSurface = true)` still carrying `prominentStyle()` elevation. In the display options issue that was the last remaining rectangle, and zeroing the dialog surface elevation removed it.

## First Checks

1. Locate the exact composable — usually `DisplayOptionsSheet`, a top-bar menu, the search sheet, or a details popup — then find its `GlassSurface` caller. Feature-owned `ModalBottomSheet` content goes through `KototoroSheetSurface`.
2. Inspect every enclosing Material container. This is the sheet pattern the codebase settled on:

```kotlin
ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
    dragHandle = null,
    shape = RoundedCornerShape(0.dp),
    containerColor = Color.Transparent,
    tonalElevation = 0.dp,
) {
    KototoroSheetSurface(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    ) { /* content */ }
}
```

3. Keep the rounded panel in one place — the inner glass surface, never both the Material sheet and the glass content.

## GlassSurface Rules

`dialogSurface = true`, which is what `KototoroSheetSurface` passes, must differ from inline cards and bars in `core/ui/glass/GlassSurface.kt`:

- It skips the liquid path outright (`isIosStyle && glassEnabled && backdrop != null && !dialogSurface`).
- It forces `tonalElevation = 0.dp`, draws no hairline border, and resolves `shadowElevation` to `0.dp` through `resolveFallbackShadowElevation(..., flat = true)`.
- Over artwork it forces an opaque `surfaceContainer` fill; the iOS path without a backdrop uses 0.98 alpha.
- `prominentStyle()` elevation must not reach a sheet or dialog without that override.

## Diagnosing ambiguous cases

Log bounds on both the Material content slot and the inner glass surface, then compare them. `DisplayOptionsSheet.kt` keeps a `BuildConfig.DEBUG`-gated `DebugBoundsBox` that only logs changed values; copy that shape instead of adding ad-hoc logging.

- Outer slot grows while the inner glass does not ⇒ suspect the Material sheet/dialog container or the window background.
- Inner glass bounds match the artifact ⇒ suspect the glass container colour, its tint, shape clipping, or `Surface` elevation.
- Coordinates change while sizes stay fixed ⇒ the artifact is a fixed layer being moved; drag motion is not the cause.
- The artifact grows while dragging and the inner glass is already tall ⇒ check whether the panel content itself is taller than expected.

From the fixed display options issue:

```text
modal_content_slot size=1272x1370
display_options_glass surface size=1188x1314
```

Both sizes held steady while dragging, so the rectangle belonged to the inner glass surface, and zeroing dialog `Surface` elevation fixed it.

## Fix Order

Apply in this order so the real cause is not masked:

1. Make the outer Material containers transparent and zero their elevation.
2. Give the rounded shape to exactly one layer.
3. Match the glass clipping and edge treatment to that same shape.
4. Drop the glass tint where a same-shape `Surface` colour already supplies the container.
5. Zero `Surface` elevation for dialog surfaces.
6. Only then disable the runtime glass path as a diagnostic, and restore it before finishing.

## Cleanup

- Remove the logging you added unless the user wants it kept. Reusable improvements such as `dialogSurface` and `resolveFallbackShadowElevation` stay, because they prevent recurrence.
- Confirm with `./gradlew :app:compileDebugKotlin --no-daemon`, and pin extracted policy with a unit test.
