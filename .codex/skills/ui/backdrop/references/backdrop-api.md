# Kyant Backdrop API Notes

These notes summarize the upstream Backdrop documentation used by the project. The canonical docs are:

- https://kyant.gitbook.io/backdrop/api/backdrops
- https://kyant.gitbook.io/backdrop/api/backdrop-effects

## Backdrop sources

- `rememberBackdrop` creates a custom backdrop.
- `rememberLayerBackdrop` is used with `Modifier.layerBackdrop` or exported from `drawBackdrop` via `exportedBackdrop`; it is coordinate-dependent.
- `rememberCombinedBackdrop` merges multiple backdrops.
- `rememberCanvasBackdrop` draws custom content into a coordinate-independent backdrop.
- `emptyBackdrop` draws nothing.

## Effects and order

`drawBackdrop` applies a chain of render effects. The documented order is:

```text
color filter -> blur -> lens
```

Useful effects include `vibrancy()`, `blur(radius, edgeTreatment)`, `lens(refractionHeight, refractionAmount, depthEffect, chromaticAberration)`, `opacity(alpha)`, and color controls. The lens effect requires a `CornerBasedShape`; its refraction height and amount must stay within the shape/component dimensions.

Backdrop effects do not define the component's semantic surface color. Draw a theme-aware surface tint as part of the destination surface, after the backdrop effect, when a control needs reliable contrast.

## Surface, highlight, and shadow

- `drawBackdrop` defaults `highlight` to `Highlight.Default` and `shadow` to `Shadow.Default`; `innerShadow` defaults to `null`.
- In Backdrop `2.0.0-alpha03`, `Shadow.Default` uses a `24.dp` blur radius, a vertical offset of `radius / 6`, and black at `0.1` alpha.
- In Backdrop `2.0.0-alpha03`, `Highlight.Default` is a directional white highlight with `0.5.dp` width and white at `0.5` alpha.
- Prefer `onDrawSurface` for the semantic surface tint. The upstream Glass Bottom Bar tutorial adds a translucent surface explicitly to improve readability.
- `drawBackdrop` already clips its recorded glass layer to the supplied shape. A same-shape `Modifier.clip` outside it can clip the native expanding shadow.
- Configure Backdrop `Shadow` explicitly when a component role needs a different depth. Do not add a Compose elevation shadow around the same Backdrop surface.
- A parent with alpha below `1f` is rendered into a Compose offscreen layer, which implicitly clips drawing outside
  the layer bounds. This can truncate an entering Backdrop shadow until a fade reaches full opacity. Prefer
  translation-only animation for glass chrome, or explicit layer outsets when the project Compose version supports them.

## Platform constraints

The upstream documentation states that Backdrop render effects require Android 12 or newer, with some RuntimeShader-based effects requiring Android 13 or newer. Treat unsupported or separate-window content as a fallback path rather than assuming the root Backdrop is available.
