# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

<!--
Document your project's database conventions here.

Questions to answer:
- What ORM/query library do you use?
- How are migrations managed?
- What are the naming conventions for tables/columns?
- How do you handle transactions?
-->

(To be filled by the team)

## Scenario: Projection Sync ID Reconciliation During Entity Repair

### 1. Scope / Trigger

This contract applies when an entity binding is inserted or moved and the
repository recalculates a deterministic sync ID from a single authoritative
projection. It is especially important during Work projection splitting:
the old entity can still own the deterministic sync ID after the binding has
been moved to a new entity.

### 2. Signatures

```kotlin
private suspend fun EntityGraphDao.reconcileProjectionSyncId(entityId: Long)

internal fun EntityRecord.resolveProjectionSyncId(
    projectionSyncId: String,
    conflictingEntityId: Long?,
): String
```

`entity.sync_id` is protected by the unique `idx_entity_sync_id` index.

### 3. Contracts

- Reconciliation only derives a deterministic projection ID when the entity
  has exactly one authoritative projection binding.
- Before updating the entity, query `findEntityBySyncId(projectionSyncId)`.
- If the owner is the same entity, the deterministic ID is valid.
- If another entity owns the ID, preserve the current non-blank `sync_id`; if
  it is blank, use a fresh UUID.
- Never overwrite an entity with a sync ID already owned by another entity.

### 4. Validation & Error Matrix

| Condition | Required result |
| --- | --- |
| No sync ID owner | Update to the deterministic projection ID |
| Owner is the current entity | Keep/use the deterministic projection ID |
| Another entity owns it and current ID is non-blank | Keep current ID and log a warning |
| Another entity owns it and current ID is blank | Assign a fresh non-blank UUID |
| More than one authoritative projection | Do not derive a projection ID |

The operation must complete without a `SQLiteConstraintException` caused by
`entity.sync_id` uniqueness.

### 5. Good / Base / Bad Cases

- Good: a newly created detached Work has a generated UUID, while the old
  entity still owns the moved projection's deterministic ID; the new entity
  keeps its UUID.
- Base: the deterministic ID is not present in the database, so it is used.
- Bad: call `updateEntity(entity.copy(syncId = projectionSyncId))` without
  checking the existing owner.

### 6. Tests Required

- Unit test the pure `EntityRecord.resolveProjectionSyncId` policy:
  - unowned ID resolves to the projection ID;
  - same-entity ownership resolves to the projection ID;
  - conflicting ownership preserves an existing ID;
  - conflicting ownership with a blank ID creates a non-blank fallback.
- Exercise the repair/split path against a database fixture where the old
  entity owns the projection sync ID and assert that the transaction commits
  and both entities have distinct sync IDs.

### 7. Wrong vs Correct

Wrong:

```kotlin
updateEntity(entity.copy(syncId = projectionSyncId))
```

Correct:

```kotlin
val owner = findEntityBySyncId(projectionSyncId)
val resolved = entity.resolveProjectionSyncId(projectionSyncId, owner?.id)
updateEntity(entity.copy(syncId = resolved))
```

The owner check is part of the persistence contract, not a UI-level repair
concern.

---

## Query Patterns

<!-- How should queries be written? Batch operations? -->

(To be filled by the team)

---

## Migrations

<!-- How to create and run migrations -->

(To be filled by the team)

---

## Naming Conventions

<!-- Table names, column names, index names -->

(To be filled by the team)

---

## Common Mistakes

<!-- Database-related mistakes your team has made -->

(To be filled by the team)
