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
  its actual content width before aligning its trailing edge to the trigger.
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
- Run `:app:compileDebugKotlin` and `git diff --check`; do not run Gradle lint
  unless explicitly requested.
