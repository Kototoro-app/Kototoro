# Component Guidelines

> How components are built in this project.

---

## Overview

<!--
Document your project's component conventions here.

Questions to answer:
- What component patterns do you use?
- How are props defined?
- How do you handle composition?
- What accessibility standards apply?
-->

(To be filled by the team)

---

## Component Structure

<!-- Standard structure of a component file -->

(To be filled by the team)

---

## Props Conventions

<!-- How props should be defined and typed -->

(To be filled by the team)

---

## Styling Patterns

<!-- How styles are applied (CSS modules, styled-components, Tailwind, etc.) -->

(To be filled by the team)

---

## Accessibility

<!-- A11y requirements and patterns -->

(To be filled by the team)

---

## Common Mistakes

<!-- Component-related mistakes your team has made -->

(To be filled by the team)

## Scenario: Adaptive Bottom Navigation Contract

### 1. Scope / Trigger

This contract applies when the main Compose chrome combines adaptive navigation,
Space-specific rail/FAB content, and the continue-reading action. The
navigation component has multiple callers, so a merge or API change must keep
all optional slots source-compatible.

### 2. Signatures

```kotlin
@Composable
fun KototoroBottomNav(
    state: StateFlow<BottomNavState>,
    onItemSelected: (Int) -> Unit,
    onItemReselected: (Int) -> Unit,
    railHeaderContent: (@Composable () -> Unit)? = null,
    adjacentAction: (@Composable () -> Unit)? = null,
    showContinueReadingButton: Boolean = false,
    onContinueReadingClick: () -> Unit = {},
)
```

`MainBottomChrome` owns the container styling and passes both groups of
optional content to `KototoroBottomNav`.

### 3. Contracts

- `railHeaderContent` remains available for Space or other rail-owned content.
- `adjacentAction` remains available for the floating/non-rail action anchor.
- `showContinueReadingButton` is enabled only when the adaptive layout is a
  navigation rail and resume is currently available.
- `onContinueReadingClick` must be the screen-level resume callback; the shared
  component must not resolve navigation or access a ViewModel directly.
- All new parameters keep defaults so legacy callers such as
  `SlidingBottomNavigationView` remain source-compatible.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Legacy caller omits new parameters | Render the existing navigation and Space slots unchanged |
| Rail layout and resume enabled | Render the continue-reading rail action and invoke the callback on click |
| Non-rail layout | Do not render the rail continue-reading action |
| Space content and resume action both present | Render both; do not replace one with the other |
| API signature changes without updating callers | Treat as a compile error and update all call sites together |

### 5. Good / Base / Bad Cases

- Good: `MainBottomChrome` forwards `railHeaderContent`, `adjacentAction`,
  `showContinueReadingButton`, and `onContinueReadingClick` in one call.
- Base: legacy callers use only the first three required parameters and rely on
  defaults for all optional content.
- Bad: choosing either the Space parameters or continue-reading parameters
  during a merge, which silently removes one feature or leaves a mismatched
  API call.

### 6. Tests Required

- Run `:app:compileDebugKotlin` after changing this contract.
- Verify no Git conflict markers or unmerged paths remain.
- If UI tests cover adaptive navigation, assert that the continue-reading
  action is present only in rail mode and that the supplied callback is used.

### 7. Wrong vs Correct

#### Wrong

```kotlin
KototoroBottomNav(
    state = navStateFlow,
    onItemSelected = onItemSelected,
    onItemReselected = onItemReselected,
    showContinueReadingButton = isResumeEnabled,
)
```

This drops Space rail/FAB content and can expose the continue action in the
wrong layout.

#### Correct

```kotlin
KototoroBottomNav(
    state = navStateFlow,
    onItemSelected = onItemSelected,
    onItemReselected = onItemReselected,
    railHeaderContent = railHeaderContent,
    adjacentAction = adjacentAction,
    showContinueReadingButton = isLandscapeNavigation && isResumeEnabled,
    onContinueReadingClick = onResumeClick,
)
```

## Scenario: Standalone Space Switcher FAB

The Space switcher FAB is a small fixed action control, not a glass panel.
Keep it independent from the shared Haze/GlassSurface pipeline so its shape
and bounds cannot inherit expanded blur layers or light-theme artifacts.

```kotlin
Surface(
    onClick = onClick,
    shape = CircleShape,
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.60f),
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
) { /* icon */ }
```

The container alpha must remain at least `0.60f`; apply alpha to the container
color rather than the whole modifier so the icon keeps its readable content
color. The rail button is a separate component and should not inherit this
FAB implementation.

## Scenario: Edge-to-Edge Rails Inside Inset Content

When a horizontal `LazyRow` lives inside a screen content column with horizontal
padding, keep the section header inset while allowing the scrolling viewport to
reach both screen edges.

### Contract

- The custom layout modifier may measure the `LazyRow` at
  `viewportWidth + startExtension + endExtension`.
- The modifier must report the original constrained `viewportWidth` to its
  parent. Reporting the expanded width violates the parent contract and causes
  asymmetric apparent placement or clipping.
- Place the expanded child at the negative start extension with
  `placeRelative`, preserving RTL behavior.
- `LazyRow.contentPadding` is content spacing, not container expansion. Use one
  start extension to keep the first item aligned with the section header; do
  not double-count the viewport extension.

```kotlin
private fun Modifier.extendHorizontalViewport(extension: Dp): Modifier = layout {
    measurable, constraints ->
    val extensionPx = extension.roundToPx()
    val viewportWidth = constraints.maxWidth.takeIf { constraints.hasBoundedWidth }
    val expandedWidth = viewportWidth?.let { it + extensionPx * 2 }
    val placeable = measurable.measure(
        expandedWidth?.let { constraints.copy(minWidth = it, maxWidth = it) } ?: constraints,
    )
    layout(viewportWidth ?: placeable.width, placeable.height) {
        placeable.placeRelative(if (viewportWidth != null) -extensionPx else 0, 0)
    }
}
```

Validation must cover grid and list rail modes, the initial item/header anchor,
the final item reaching the end edge, and an unbounded-width fallback that does
not apply a negative offset.

## Scenario: Adaptive Menu Width With Full-Row Hit Targets

When a compact menu wraps its content width, determine one shared intrinsic
width for the menu before making each action row fill that width. Otherwise the
panel follows its longest label while shorter rows remain clickable only over
their own text or icons.

```kotlin
Column(Modifier.width(IntrinsicSize.Max)) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) { /* icon and label */ }
}
```

Keep `clickable` before visual padding so the padding and trailing blank area
belong to the same pointer and semantics target. Do not use `fillMaxWidth()` on
rows inside an otherwise unconstrained wrapping parent without first defining
the parent's intrinsic or explicit width; doing so can expand a root overlay to
its maximum allowed width instead of its content width.

Validation must cover a menu with labels of different lengths, a row with a
leading or trailing control, clicks near both horizontal edges, the fixed-width
Popup path, and the content-wrapping root-overlay path.
