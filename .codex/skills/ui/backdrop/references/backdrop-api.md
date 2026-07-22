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

## Platform constraints

The upstream documentation states that Backdrop render effects require Android 12 or newer, with some RuntimeShader-based effects requiring Android 13 or newer. Treat unsupported or separate-window content as a fallback path rather than assuming the root Backdrop is available.
