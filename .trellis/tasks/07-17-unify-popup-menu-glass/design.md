# Technical Design

## Boundary

`GlassDropdownMenu` remains the single Compose menu API. The existing `RootGlassMenuHost` and `RootGlassMenuOverlay` become the iOS same-window rendering path for every eligible menu. Call sites provide measured anchor bounds and whether the menu opens above the anchor.

## Rendering Contract

- A menu consumer is rendered by `RootGlassMenuOverlay` as a sibling of the route content, after the registered Backdrop source.
- `DropdownMenu` remains only for cross-window or unavailable-host fallback. Its iOS path is static and must not enable Haze.
- The root overlay uses the same surface fill, `vibrancy`, blur, lens, border, content color, and compact row components as the main “More” menu.
- Bottom dock menus open above their trigger; top-bar and list menus open below their trigger.

## Call-site Contract

Each eligible menu owns a `Rect?` anchor state updated with `boundsInRoot()` on the trigger container. It passes `useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS`, the anchor bounds, and placement direction to `GlassDropdownMenu`. Menus without a root host continue to use the existing safe fallback.

## Content Contract

Replace raw Material3 `DropdownMenuItem`/`Text` usage in the scoped menus with the shared compact row/text primitives. Existing selection indicators and icons remain as slot content, so action behavior is unchanged.

## Trade-offs

Root overlays require anchor measurement and a single host, but are necessary because Backdrop coordinates cannot cross Compose Popup windows. Keeping fallback behavior preserves non-iOS compatibility and avoids the RenderThread crash caused by making a consumer sample a source subtree that contains itself.
