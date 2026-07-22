---
name: backdrop
description: Kyant Backdrop glass effects for Compose, including drawBackdrop, layerBackdrop, blur, vibrancy, lens, exported backdrops, and theme-aware surface tinting. Use when working on Kototoro iOS-style glass controls, Backdrop rendering order, contrast problems, or Backdrop regressions.
---

# Backdrop

Use this skill for Kototoro's `io.github.kyant0:backdrop` integration. The app uses Backdrop for the iOS interface style and Haze for the other runtime glass path; do not substitute Haze APIs when the affected code imports `com.kyant.backdrop`.

## Workflow

1. Locate the `drawBackdrop` call and identify its source (`LocalLiquidGlassBackdrop`, `rememberLayerBackdrop`, or `layerBackdrop`). Confirm the source and destination are in the same window; Popup/Dialog content normally needs the regular `GlassSurface` fallback.
2. Read the complete modifier chain. Backdrop effects transform the sampled backdrop, but they do not provide an opaque semantic container color. Add a theme-derived surface tint after the backdrop effect when content needs stable contrast.
3. Keep effect order as `color filter -> blur -> lens`. `lens` requires a `CornerBasedShape`; use a simpler shape or omit lens for arbitrary shapes.
4. Keep colors theme-aware. Never use an opaque/fixed white surface as the iOS default. Derive the base from `MaterialTheme.colorScheme.surfaceContainer` (or a more suitable surface role), then apply a modest alpha after `drawBackdrop`.
5. Preserve content color separately from the surface tint. Use `MaterialTheme.colorScheme.onSurface` for standard iOS glass controls unless the component has a stronger semantic role.
6. Validate with `./gradlew :app:compileDebugKotlin --no-daemon`. Add or update a focused unit test when extracting pure color or modifier-policy logic.

## Kototoro conventions

- iOS detection is `LocalInterfaceStyle.current == InterfaceStyle.IOS`.
- The active Backdrop is provided by `LocalLiquidGlassBackdrop.current`.
- Existing shared glass code belongs in `core/ui/glass` or `core/ui/compose`; prefer a small `Modifier` helper over duplicating a long `background + drawBackdrop + border` chain.
- `drawBackdrop` can use `exportedBackdrop = rememberLayerBackdrop()` when a child surface should feed another Backdrop surface.
- Surface tint must be drawn after the visual effect. If using `onDrawSurface`, draw the theme color there; otherwise place a `.background(...)` modifier after `.drawBackdrop(...)`.
- Popup windows have a different coordinate space. Do not assume the root Backdrop is valid inside a Popup; use the existing root overlay route or fallback surface pattern.

## Diagnosis checklist

- Is the control's surface tint fixed to `Color.White` or `Color.Black`?
- Is the tint before `drawBackdrop`, and therefore potentially hidden by the effect?
- Is the control inheriting a transparent container from Material components?
- Is `onDrawSurface` already drawing a tint, causing an overly opaque double layer?
- Does the selected icon/text color match the polarity of the theme surface?
- Does a `lens` effect receive a `CornerBasedShape` and valid dimensions?

## References

Read [references/backdrop-api.md](references/backdrop-api.md) for the upstream API facts used by this skill, especially when changing effect order, backdrop sources, or surface drawing.
