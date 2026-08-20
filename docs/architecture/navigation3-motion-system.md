# Kototoro Navigation 3 Motion System

## Goals

Kototoro's app navigation has been cut over to `androidx.navigation3` (Phase A–C): the outer `NavHost` is now only the shell, and the immersive destinations (content list, details, search) are plain `NavKey`s pushed onto per-tab `MainNavState` back stacks and rendered by a single `MainTopLevelNavDisplay`. This document defines the motion language that governs how those scenes transition.

The goal is **semantics-first motion**: every navigation relation maps to one of a small set of motion presets, configured once at the `NavDisplay` level and overridden per scene through `NavMetadataKey`s. No per-screen ad-hoc timings, no turning navigation into decoration.

## Design Principles

1. **Animation expresses the navigation relation, not decoration.** Sibling switches, parent–child depth, entity expansion, immersive consumption, and transient surfaces each have their own motion language.
2. **One scene keeps one dominant motion.** A hero (shared element) transition does not stack a full page slide; a `ModalBottomSheet` does not stack a `NavDisplay` slide.
3. **Entity continuity beats page continuity.** List → details is not "a new page" but "this work unfolded": cover `sharedElement` and hero `sharedBounds` dominate over any horizontal slide.
4. **Predictive back is the interactive reverse of the enter animation.** Navigation 3 has an independent `predictivePopTransitionSpec` channel, and shared elements participate in the predictive gesture.
5. **Motion intensity scales with hierarchy depth.** Top-level switches are lightest, parent–child moderate, entity expansion strongest, reader/player deliberately restrained.

## Research Basis (official docs and issues)

- **Animate between destinations** (Android Developers, Navigation 3):
  `NavDisplay` supports global `transitionSpec` / `popTransitionSpec` / `predictivePopTransitionSpec`, and per-scene overrides through the `NavDisplay.TransitionKey` / `PopTransitionKey` / `PredictivePopTransitionKey` `NavMetadataKey`s written with the `metadata {}` DSL. When a scene defines no transform, `NavDisplay` falls back to the corresponding global default. Wrapping `NavDisplay` in a `SharedTransitionLayout` and passing the `sharedTransitionScope` removes jumpy entry transitions between scenes.
  https://developer.android.com/guide/navigation/navigation-3/animate-destinations
- **Navigation with shared elements** (Compose):
  `SharedTransitionLayout` must wrap `NavDisplay`; each screen uses `LocalNavAnimatedContentScope.current` as the `AnimatedVisibilityScope` for `Modifier.sharedElement` / `sharedBounds` with `rememberSharedContentState`. Predictive back is supported by **all** Navigation 3 versions; enabled by default on Android 15+ (API 35+) and gated behind a developer option on API 34; with `targetSdk 37` and `android:enableOnBackInvokedCallback` already set, Kototoro exercises predictive back on API 36 devices.
  https://developer.android.com/develop/ui/compose/animation/shared-elements/navigation
- **Set up predictive back** (Compose): default system animations on Android 15+.
  https://developer.android.com/develop/ui/compose/system/predictive-back-setup
- **nav3-recipes issues**:
  - `#86` — Navigation 3 intentionally does not map `NavKey` data into `SavedStateHandle`; the sanctioned pattern is injecting the key directly into the ViewModel (already applied: `SearchViewModel` uses `@AssistedInject`; the source name travels through `PendingContentListNavigation`).
  - `#253` — scene-level metadata transitions are the current recommended API (docs cover them; Kototoro already uses them).
  - `#248` — a "sliding backstack" requires a custom `SceneStrategy` (adaptive/multi-pane). Kototoro is single-pane; not in scope.
  - `#212` — animation gaps with multi-pane `ListDetailSceneStrategy`; unaffected for single-pane `SinglePaneSceneStrategy`.

## Motion Primitives

| Semantics | Primitive | Motion |
|---|---|---|
| Sibling switches (tabs, spaces) | **FadeThrough** | alpha crossfade + very light scale (≈0.985), no horizontal travel |
| Parent–child depth (list → category/source, settings → sub-page, details → tracking) | **Hierarchical** | restrained horizontal shared axis (≈1/3 width) + fade; not a full page turn |
| Work entity expansion (list → details) | **HeroExpand** | cover `sharedElement` + card/hero `sharedBounds` + Z/Y expansion + backdrop reveal + staggered detail rows; no full-width slide |
| Immersive consumption (details → reader/player) | **ImmersiveFade** | short fade + very light scale; video players even more restrained |
| Tool workspace (search, full-screen filter layers) | **ZAxisLayer** | forward/back depth: incoming scale 1.03→1, outgoing scale 1→0.97, plus fade |
| Sheets / dialogs / menus / tables / pager | **ComponentMotion** | owned by Material components, never the `NavDisplay` |

## Kototoro Mapping

| Surface | Current (Phase C) | Target |
|---|---|---|
| Top-level tab switch (10 tabs) | `NavDisplay` default | **FadeThrough** (global default) |
| Space switch | same-stack relayout | **FadeThrough** |
| Explore → source (`ContentListNavKey`) | full-width horizontal slide | **Hierarchical** |
| Content list → details (`DetailsNavKey`) | full-width slide + hero + backdrop + floats | **HeroExpand** (drop full-width slide; keep light horizontal depth + Z/Y + hero/backdrop/floats) |
| Search (`SearchNavKey`) | default fade | **ZAxisLayer** |
| Reader / player (outside Navigation 3 today) | n/a | **ImmersiveFade** when migrated |
| Sheets (chapters, filter, sort, reader settings) | Material 3 native | unchanged |

Drop the full-width details slide deliberately: the confirmed-good on-device experience (cover hero + backdrop + floating elements) should dominate, and a full page turn on top of it is over-animation. A shallow horizontal depth (8–12 % width) is kept so the directionality users already verified is preserved.

## Implementation Architecture

All motion is decided in one place: `MainTopLevelNavDisplay`.

```kotlin
enum class KototoroMotion { FadeThrough, Hierarchical, HeroExpand, ZAxisLayer, ImmersiveFade }

// (prevTop, top) relation -> preset; fall back to FadeThrough for tab/space switches.
fun KototoroMotion.forRelation(prev: MainNavKey?, top: MainNavKey): KototoroMotion
```

- `NavDisplay` receives the global **FadeThrough** triple (`transitionSpec` / `popTransitionSpec` / `predictivePopTransitionSpec`) — this covers tab and space switches, which re-enter `NavDisplay` through a changed entries list.
- Each entry adds per-scene metadata (already the mechanism in place) keyed by `KototoroMotion.forRelation(prev, key)`:

```kotlin
return metadata {
    put(NavDisplay.TransitionKey, preset.enter)
    put(NavDisplay.PopTransitionKey, preset.pop)
    put(NavDisplay.PredictivePopTransitionKey, preset.predictivePop)
}
```

- Presets are pure functions over `AnimatedContentTransitionScope<Scene<*>>` returning `ContentTransform`, composed from `MainNavigationMotion` timings (extended with per-primitive constants).
- Shared elements and predictive back ride the existing infrastructure: root `SharedTransitionLayout`, `LocalNavAnimatedContentScope.current` provided per entry, `PredictivePopTransitionKey` defined on each preset. API 36 test devices exercise the predictive gesture without extra flags.

### Not in scope

- Multi-pane / adaptive `SceneStrategy` (sliding backstack, two-pane) — Kototoro is single-pane.
- Reader / player transitions — separate surfaces outside Navigation 3; adopt **ImmersiveFade** when they migrate.
- `ModalBottomSheet` / dialogs / menus / in-tab pager — Material components keep their own motion.
- Any per-destination ad-hoc timings.

## Rollout and Verification

1. Extract `KototoroMotion` and preset table (behavior-preserving refactor of today's slide). Gate: `:app:compileDebugKotlin` + `:app:compileDebugUnitTestKotlin`.
2. Apply semantics: ContentList → **Hierarchical**, Details → **HeroExpand**, Search → **ZAxisLayer**, global **FadeThrough**. Gate: compile + targeted space JVM tests + arm64 APK install.
3. On-device manual check on `ecd4369c` (API 36): tab switch = fade; Explore → source = shallow axis with list load; content list → details = hero + backdrop + floats (no full-width page turn); back gesture (predictive) reverses hero; search = Z-axis.
4. Conventional commit per step (`feat(nav3): ...` / `refactor(nav3): ...`).
