# Entity Graph Source Boundary Audit (2026-06)

## Purpose

This note records the current boundary problems between entity identity, local reading projections, tracking bindings, and metadata source selections.

The immediate trigger was an entity-organize repair scan where more than 90% of reported "suspect tracking binding" issues were not entity tracking bindings. They were per-manga metadata source selections that had been mirrored from an entity-level tracking source.

## Observed Evidence

Diagnostic logging from `EntityGraphRepository.inspectRepairIssues()` produced:

```text
repair tracking suspect diagnostics: total=198 branches={entity_tracking_binding=4, manga_metadata_source=194}
```

The branch split is important:

| Branch | Meaning | Count |
| --- | --- | ---: |
| `entity_tracking_binding` | Entity-level tracking binding mismatch | 4 |
| `manga_metadata_source` | Per-manga metadata source mismatch | 194 |

The repair UI currently surfaces these together as suspect tracking binding risk. That makes the problem appear much larger than the actual entity-binding failure set.

Representative samples showed:

- an entity may have the correct tracking title in `entityKeys`, but a specific local manga title is a translation, abbreviation, simplified/traditional variant, or source-specific title;
- some local manga metadata selections pointed to clearly unrelated tracking records;
- many false positives came from strict title-key equality between a single local manga title and a tracking title, even when the entity already knew compatible aliases.

## Current Concept Overload

The current implementation has too many mutable source-of-truth layers:

| Concept | Intended role | Current risk |
| --- | --- | --- |
| Entity | Aggregate identity for one work | Can absorb unrelated local works through merge or tracking ingest |
| Entity binding to `local_manga` | Local reading projection | Also used as evidence for metadata-source mirroring |
| Entity binding to tracking source | External work identity | Mixed in repair reporting with per-manga metadata issues |
| `tracking_site_link` | Per-manga tracking match cache/history | Can be treated like authoritative binding evidence |
| Entity metadata source | Preferred metadata authority for the aggregate | Mirrored to every local projection in some flows |
| Per-manga metadata source | Legacy or explicit single-item override | Can be polluted by entity-level choices and later reported as binding corruption |
| Preferred local manga id | Preferred reading projection for an entity | Separate from metadata selection and tracking identity, but often updated nearby |

The core problem is not one table. It is that identity, projection, metadata authority, and recommendation cache are allowed to overwrite or mirror each other.

## Root Cause

`ContentDataRepository.setEntityMetadataSourceSelection()` supports `mirrorLocalMangaIds`.

Several flows pass all local bindings of an entity as mirror targets:

- merge execution after selecting a preferred tracking source;
- tracking bind execution;
- details metadata source persistence for an entity.

When an entity has multiple local projections, a single entity-level tracking source is copied into each local manga preference row. If the entity later contains unrelated projections, or if local titles differ from tracking titles by language/script/romanization, the repair scan reports many per-manga metadata source mismatches.

This creates two distinct failure modes:

1. Real data pollution: unrelated local manga receives a tracking metadata source that does not describe it.
2. False-positive reporting: a valid entity-level metadata source looks incompatible when checked against only one local projection title.

## Required Boundary Rules

### Entity Is The Identity Center

Entity-level tracking bindings should represent external work identity.

Rules:

- Tracking identities such as AniList, MAL, Bangumi, MangaUpdates, and similar sources should bind primarily to the entity.
- Entity tracking bindings should not be automatically duplicated into every local manga as authoritative per-manga metadata.
- Entity-level metadata source selection should be evaluated against entity aliases and known local projections together.

### Local Manga Is A Reading Projection

A local manga row is a projection from one reading source.

Rules:

- `local_manga` bindings should answer "which source item can be opened/read for this entity?"
- They should not independently redefine the entity's tracking identity.
- Per-manga metadata source should be treated as an explicit override or legacy compatibility field.

### `tracking_site_link` Is Cache Or Audit Data

Per-manga tracking links can remain useful, but they should not be conflated with entity bindings.

Rules:

- Keep `tracking_site_link` as match cache, user confirmation history, or suggestion suppression state.
- Do not count every stale per-manga tracking link as an entity tracking binding failure.
- Rejection tools should distinguish "reject entity tracking binding" from "clear per-manga cached match".

### Metadata Source Selection Should Not Be Blindly Mirrored

Entity metadata source and per-manga metadata source need different semantics.

Rules:

- Entity metadata source is the default metadata authority for entity details.
- Per-manga metadata source is an override for a specific local manga detail context.
- Automatic mirroring should be opt-in and narrow, not the default for all entity projections.
- Mirroring a tracking metadata source should be limited to local manga whose title or aliases are compatible with that tracking source, unless the user explicitly confirms the override.

## Reporting Fixes

Repair reporting should use precise categories:

| Category | Should include | Should not include |
| --- | --- | --- |
| `SUSPECT_TRACKING_BINDING` | Entity-level tracking bindings that conflict with entity/local evidence | Per-manga metadata source mismatches |
| `SUSPECT_METADATA_SOURCE` | Entity metadata source or per-manga metadata source that points to an incompatible tracking item | Entity binding rows |
| `CONFLICTING_READING_BINDING` | Multiple active local reading bindings for the same source item across entities | Metadata source drift |
| `SUSPECT_MISMERGED_LOCAL_WORK` | Local projection whose title keys conflict with entity identity | Tracking suggestion cache mismatch |

The current log evidence suggests the UI count for suspect tracking binding should drop from 198 to 4 after separating `manga_metadata_source` into metadata-source repair.

## Short-Term Remediation

1. Stop counting `manga_metadata_source` mismatches as `SUSPECT_TRACKING_BINDING`.
2. In per-manga metadata-source checks, consider entity alias compatibility before reporting a mismatch.
3. Stop mirroring entity tracking metadata source to all local manga bindings by default.
4. When mirroring is needed, mirror only to compatible local projections or a user-selected projection.
5. Add repair actions with separate labels:
   - reject suspect entity tracking binding;
   - clear stale per-manga metadata source;
   - clear stale per-manga tracking cache/link.

## Medium-Term Data Model Direction

The desired model is:

```text
Entity
  - identity and aliases
  - entity-level tracking bindings
  - entity-level metadata source preference
  - preferred local reading projection

Local Manga Binding
  - reading source projection
  - source-specific open/read/update behavior

Tracking Site Link
  - per-manga match cache, suggestion, or audit trail
  - not the primary entity identity

Per-Manga Metadata Source
  - explicit override only
  - legacy field retained for compatibility
```

Preferences should eventually point to bindings where possible, not only raw service and remote ids. That would make it clearer whether a metadata source is entity-level or projection-level, and would reduce orphaned or stale ids.

## Diagnostic Guidance

When investigating future spikes, inspect the branch split first:

```text
repair tracking suspect diagnostics: total=N branches={...}
```

Interpretation:

- High `entity_tracking_binding`: likely real entity identity corruption.
- High `manga_metadata_source`: likely metadata mirroring drift or overly strict single-projection title matching.
- High `tracking_site_link`: likely stale per-manga cache/link data.
- High `entity_metadata_source`: entity-level metadata source points to an incompatible tracking item.

This distinction should guide repair behavior and UI wording.

