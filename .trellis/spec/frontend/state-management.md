# State Management

> How state is managed in this project.

---

## Overview

<!--
Document your project's state management conventions here.

Questions to answer:
- What state management solution do you use?
- How is local vs global state decided?
- How do you handle server state?
- What are the patterns for derived state?
-->

(To be filled by the team)

## Feature-Gated Runtime State

When a runtime feature has a master setting, the disabled path must short-circuit
at the Flow boundary before creating downstream subscriptions. UI visibility
checks alone are insufficient because `combine` subscribes to every upstream
Flow even when the result is immediately `null`.

```kotlin
fun observeAllowedSourceNames(spaceIds: Flow<SpaceId?>) =
    spaceIds.flatMapLatest { spaceId ->
        if (spaceId == null) {
            flowOf(null)
        } else {
            combine(spaceCatalog, observeSources()) { spaces, sources ->
                resolve(spaceId, spaces, sources)
            }
        }
    }
```

Controllers that perform feature-specific persistence should expose idempotent
`start()` and cancellable `stop()` operations. The activity or screen should
sync them from the master setting on creation and lifecycle resume so a setting
changed in a separate settings screen takes effect without recreating the
activity.

Required tests should assert both the disabled output and that the expensive
upstream factory was not called.

---

## State Categories

<!-- Local state, global state, server state, URL state -->

(To be filled by the team)

---

## When to Use Global State

<!-- Criteria for promoting state to global -->

(To be filled by the team)

---

## Server State

<!-- How server data is cached and synchronized -->

(To be filled by the team)

---

## Common Mistakes

<!-- State management mistakes your team has made -->

(To be filled by the team)
