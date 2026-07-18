# Technical Design

## Boundaries

- Resource layer: update only appearance-related string values across maintained locale files.
- Compose layer: reuse the existing `SpaceSwitcherFab` visual recipe as the reference for iOS style.
- View integration layer: extend `SpaceSwitcherDelegate` and the immersive Activity roots without changing space-switch domain logic.

## Backdrop Strategy

The XML `ExtendedFloatingActionButton` remains the lifecycle and interaction anchor so existing delegate code continues to control visibility, animation, accessibility, and click handling. For iOS style, a Compose overlay is attached to the FAB parent and positioned behind the transparent View FAB. The overlay renders a circular static glass surface; the View FAB stays above it so touch dispatch remains reliable.

The overlay must be disabled for non-iOS style and removed with the Activity lifecycle. Backdrop sampling is intentionally not used for the View host because Android View content is not a reliable source for the Compose layer. The static translucent surface is the explicit fallback and prevents rectangular sampling artifacts.

## Data Flow

`AppSettings.interfaceStyle` -> Activity/Compose theme style observation -> `SpaceSwitcherDelegate` FAB presentation state -> overlay visibility/style -> existing delegate click and state updates.

## Compatibility

- Existing XML layouts and delegate APIs remain usable for all three View hosts.
- No preference migration is required; only display labels and presentation are changed.
- The implementation must not make View activities depend on main-screen Compose composition locals.

## Trade-off

An overlay avoids rewriting reader/player screens, but Backdrop sampling across Android View content may be renderer-dependent. The explicit static fallback is required for correctness and accessibility.
