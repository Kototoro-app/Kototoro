# Technical Design

## Boundary

The runtime master flag remains the single source of truth. The optimization
only changes subscription and collection lifetimes; it does not introduce a
second Space-enabled state or change the existing effective-flag semantics.

## Data flow

```text
AppSettings.KEY_ENTITY_SPACE_ENABLED
        -> SpaceFeatureFlagsRepository
        -> MainActivity / SpaceBrowseScope / Space controllers
        -> disabled short-circuit or normal Space behavior
```

When the effective Space state is disabled:

- `SpaceBrowseScope` returns `null` without subscribing to enabled sources.
- MainActivity supplies default empty Space UI state and does not collect Space
  ViewModel flows.
- Space controllers are started only when the master flag is enabled. The
  existing controller guards remain responsible for Space-specific behavior.

When the flag is enabled, existing repositories and flows are used unchanged.

## Compatibility

- Keep all Room entities, migrations, repositories, and settings keys.
- Preserve runtime toggling. MainActivity rechecks the master flag when its
  lifecycle resumes so enabling Space from Settings can start the controllers.
- Do not use a separate hard-coded disabled implementation; that would create
  duplicate behavior and drift from the existing Space contracts.

## Trade-offs

The first implementation targets the measurable runtime overhead in the
disabled path. It intentionally does not remove compiled Space code or schema
overhead, which requires a separate build variant and is outside this task.
