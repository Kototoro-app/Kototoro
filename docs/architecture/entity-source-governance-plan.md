# Entity Source Governance Plan

## Purpose

This plan defines how Kototoro should govern entities across favourites, history, updates, subscriptions, and local content.

The current bug class comes from a blurred boundary: source records, tracking metadata, and entity graph aggregation can overwrite each other's identity. The target model is stricter:

- page records keep their native source identity
- entities are an optional aggregation layer
- tracking sources provide metadata authority
- reading sources provide content execution
- manual entity organization always wins over automatic inference

The goal is not to make every item an entity. The goal is to make entity use explicit, reversible, source-aware, and safe under cross-device sync.

## Non-Goals

- Do not replace reading source records with entities.
- Do not make history or updates auto-create entities.
- Do not use name similarity as a final binding decision.
- Do not delete existing legacy relations blindly.
- Do not introduce a remote backend or graph database.

## Core Product Model

### Source Record

A source record is the native item owned by a feature page:

- favourites entry
- history entry
- update candidate
- subscription entry
- local import item
- reading source `Content`
- tracking source remote item

Source records are the source of truth for their own feature behaviour. For example:

- history opens the exact item the user read
- updates check the subscribed reading source item
- local page opens the imported local item
- favourites keep the saved source item unless the user explicitly changes it

### Entity

An entity is a local aggregation object for a work, character, person, or organization.

Entities provide:

- unified metadata selection
- cross-source source list
- manual organization
- relation navigation
- tracking-driven discovery context

Entities must not silently replace the source record that a page owns.

### Binding

A binding links an entity to a source record.

Bindings must carry enough provenance to answer:

- which source kind created this link
- whether this link is manual or automatic
- whether the user rejected this link
- which device or sync event created it
- whether relations generated from this link are still valid

### Preference

Preferences define how an entity should be presented:

- selected metadata source
- selected reading source
- preferred local projection
- manual title or cover override

Preferences should point to bindings, not only raw ids. This prevents orphaned remote ids from surviving sync without the source record they reference.

## Required Data Model Evolution

### `entity`

Keep the existing entity table focused:

- `id`
- `type`
- `primary_name`
- `aliases`
- `created_at`
- `last_accessed`
- `access_count`

Optional manual presentation fields can be added later, but they should not be required for this governance layer.

### `entity_binding`

Extend binding semantics instead of overloading `source` and `external_id`.

Recommended fields:

| Field | Purpose |
| --- | --- |
| `entity_id` | Target entity |
| `source_kind` | `reading_source`, `tracking_source`, `local`, `manual` |
| `source_id` | Parser source id, scrobbler id, or local namespace |
| `external_id` | Source-native id |
| `confidence` | Matching confidence |
| `state` | `manual`, `confirmed`, `candidate`, `rejected`, `legacy` |
| `is_primary` | Preferred binding within the same kind |
| `created_by` | `user`, `matcher`, `ingest`, `sync`, `migration` |
| `created_device_id` | Optional sync diagnostics |
| `created_at` | Audit and conflict resolution |
| `updated_at` | Audit and conflict resolution |

Rules:

- `manual` bindings cannot be overwritten by automatic matching.
- `rejected` bindings block future automatic matches for the same source key.
- `candidate` bindings are shown in UI but not used for page routing.
- `legacy` bindings are read-only compatibility data until repaired or confirmed.

### `entity_preference`

Store preferences by binding id where possible:

| Field | Purpose |
| --- | --- |
| `entity_id` | Target entity |
| `metadata_binding_id` | Selected tracking metadata binding |
| `reading_binding_id` | Selected reading source binding |
| `preferred_local_binding_id` | Selected local projection |
| `manual_title` | Optional user override |
| `manual_cover_url` | Optional user override |
| `updated_at` | Sync conflict resolution |

Fallback raw ids can exist during migration, but final logic should prefer binding ids.

### `entity_relation`

Relations need provenance. Without provenance, stale tracking-source relations cannot be separated from current metadata.

Recommended fields:

| Field | Purpose |
| --- | --- |
| `from_entity_id` | Source entity |
| `to_entity_id` | Target entity |
| `type` | Relation type |
| `source_binding_id` | Binding that generated the relation |
| `relation_origin` | `tracking_ingest`, `manual`, `migration`, `legacy` |
| `state` | `active`, `hidden`, `rejected`, `legacy` |
| `weight` | Ranking |
| `created_at` | Audit |
| `updated_at` | Audit |

Rules:

- Work details using a selected metadata binding only show relations generated from that binding, plus manual relations.
- Legacy relations are hidden on work details when a selected metadata source exists.
- Manual relations are always visible unless explicitly hidden.
- Switching metadata source changes visible relations without deleting old relation rows.

### Candidate Table

Automatic matching should produce candidates, not direct entity mutations.

Recommended table:

| Field | Purpose |
| --- | --- |
| `candidate_id` | Stable candidate id |
| `entity_id` | Suggested entity |
| `source_kind` | Suggested source kind |
| `source_id` | Suggested source id |
| `external_id` | Suggested source item |
| `confidence` | Match score |
| `reason` | Match explanation |
| `state` | `pending`, `accepted`, `rejected`, `expired` |
| `created_at` | Audit |

Candidates can be rendered as "possible same work" chips or entity workbench rows.

## Feature Page Rules

### Favourites

Favourites are the safest place to create or confirm entities because saving is explicit user intent.

Rules:

- Favourites may offer entity creation.
- High-confidence matches may be shown as candidates.
- Do not merge favourites into an existing entity without user confirmation when multiple local bindings are involved.
- Opening a favourite should open the saved source item unless the user selected an entity-first mode.

### History

History must be read-only with respect to entity creation.

Rules:

- History must not call entity creation or merge APIs during list rendering.
- History may resolve existing explicit bindings for badges and grouping.
- History item click should preserve the original source item as the initial projection.
- If an entity is shown, it must not override the clicked history item's reading source.

### Updates

Updates are reading-source execution records, not entity records.

Rules:

- Update checks run against reading source bindings or native subscription records.
- Entity grouping can be used for display, but update freshness belongs to the reading source item.
- Switching metadata source must not change update source.
- Failed entity resolution must not block update checks.

### Subscriptions

Subscriptions should bind to reading source records first and optionally to entities.

Rules:

- Subscription identity is the reading source key.
- Entity id is an optional grouping key.
- Manual subscription-to-entity binding is allowed.
- Auto subscription matching should create candidates only.

### Local Page

Local content is a first-class source kind.

Rules:

- Local imports can be bound to entities.
- Local import names should not auto-bind by name alone.
- Local items can become the preferred reading projection if the user chooses them.
- Local deletion must not delete the entity unless no non-local bindings remain and pruning rules allow it.

## Detail Page Rules

### Work Detail

Work detail needs two independent axes:

- metadata source
- reading source

Rules:

- Metadata source controls title, cover, description, staff, characters, related works, and external actions.
- Reading source controls chapters, videos, downloads, and update execution.
- Changing metadata source must refresh relation sections from that metadata source.
- Changing reading source must not change metadata source.
- If selected metadata source has no staff or character data, do not fall back to legacy relations from a different tracking source.

### Entity Detail

Entity detail should be the management shell:

- show all bindings grouped by source kind
- show selected metadata source
- show selected reading source
- show candidates
- support accept, reject, detach, merge, split, and set preferred source

### Raw Tracking Detail

Raw tracking detail remains useful as an inspection fallback:

- open raw tracking page
- inspect remote metadata
- compare with current entity
- bind or reject the remote item

It should not be the only place where entity decisions are made.

## Repository Boundaries

### EntityGraphRepository

`EntityGraphRepository` should be the only writer for:

- entity creation
- binding creation
- binding state changes
- relation ingestion
- entity merge or split

Callers should not write raw DAO rows except migrations and backup restore.

### Page Repositories

Feature repositories own native page records:

- favourites repository owns favourite entries
- history repository owns history rows
- update repository owns update rows
- subscriptions own subscription rows
- local repository owns local content rows

They may ask `EntityGraphRepository` to resolve existing bindings, but they should not create entities during passive list rendering.

### Resolver API

Introduce explicit resolver methods:

```kotlin
suspend fun resolveExistingEntityForSource(
    sourceKind: SourceKind,
    sourceId: String,
    externalId: String,
): Entity?

suspend fun proposeEntityCandidates(
    sourceRecord: SourceRecord,
): List<EntityCandidate>

suspend fun bindSourceToEntity(
    entityId: Long,
    sourceRecord: SourceRecord,
    mode: BindingMode,
): EntityBinding
```

This separates "read existing binding", "suggest possible binding", and "commit binding".

## Sync Governance

Cross-device sync must not silently upgrade uncertain data.

Rules:

- Sync can import `manual`, `confirmed`, `rejected`, and `legacy` states.
- Sync should not convert `candidate` into `confirmed`.
- Conflict resolution prefers newer manual changes over automatic changes.
- If a preference references a missing binding, keep the preference as invalid and surface repair UI instead of falling back to another source.
- Imported legacy relations should remain `legacy` until regenerated from a known binding.

Recommended conflict priority:

1. user manual update
2. user rejection
3. confirmed binding
4. synced legacy binding
5. local automatic candidate

## Migration Plan

### Phase 0: Guardrails

Already started by recent fixes:

- history list should resolve existing entity ids only
- details should preserve clicked source projection
- metadata preference writes should validate entity and manga existence
- selected tracking metadata should override work relation display

Additional Phase 0 tasks:

- audit all passive list renderers for entity creation calls
- block automatic entity creation from history, updates, subscriptions, and local list rendering
- add logs for any entity merge triggered outside explicit user actions or tracking-detail ingestion

### Phase 1: Binding Semantics

Add binding states and source kinds.

Tasks:

- migrate existing `source` values into `source_kind`, `source_id`, and `external_id`
- mark existing rows as `legacy` or `confirmed` based on confidence and source
- add DAO filters for active bindings
- ensure rejected bindings block future automatic matching

Exit criteria:

- page renderers use existing active bindings only
- candidates are visible but do not affect routing
- manual bindings survive automatic matching

### Phase 2: Relation Provenance

Add relation provenance and regenerate tracking-derived relations.

Tasks:

- add `source_binding_id`, `relation_origin`, `state`, and `updated_at`
- mark existing relation rows as `legacy`
- update tracking ingest to write relations with source binding provenance
- filter work details by selected metadata binding
- show manual relations regardless of metadata source

Exit criteria:

- switching metadata source changes creator, character, and related-work sections deterministically
- legacy relations are hidden when selected metadata exists
- no destructive cleanup is needed to fix stale relation display

### Phase 3: Entity Organize Workbench

Reuse the existing manual management surface in Settings -> Entity Organize.
Do not build a second workbench. Details, migration, and source repair flows should link into or share backend APIs with this existing surface.

Required operations:

- set metadata source
- set reading source
- attach source item
- detach binding
- accept candidate
- reject candidate
- merge entities
- split binding into a new entity
- mark relation hidden or manual

Exit criteria:

- users can repair every wrong automatic decision without database surgery
- rejected matches do not reappear after refresh or sync
- source preferences are understandable from the UI
- existing Entity Organize UI can call the same repository APIs as details and migration flows

### Phase 4: Page Integration

Apply consistent rules to all feature pages.

Tasks:

- favourites: entity badge, candidate action, optional entity-first open
- history: native open with optional entity context
- updates: reading-source-first checks, entity grouping only
- subscriptions: source-key identity with optional entity grouping
- local: local source binding management

Exit criteria:

- every page has a documented entity policy
- no page loses its native source identity after entity matching
- all entity actions are explicit or reversible

### Phase 5: Cleanup and Repair Tools

Add maintenance tools for existing users.

Tasks:

- scan for orphan preferences
- scan for legacy relations attached to multiple metadata sources
- scan for entities with conflicting reading source bindings
- expose "repair entity" entry from details and Entity Organize
- optionally prune unused legacy relations after user confirmation

Exit criteria:

- sync-imported contamination can be diagnosed
- stale bindings are not silently used
- destructive cleanup requires explicit user confirmation

## Testing Strategy

### Unit Tests

Required cases:

- history rendering does not create entities
- update rendering does not create entities
- candidate does not affect routing
- rejected binding blocks auto-match
- manual binding cannot be overwritten
- metadata source switch filters relations by provenance
- missing preference binding does not fall back silently

### Integration Tests

Required flows:

- favourite item binds to entity, then opens saved source item
- history item under an entity opens the clicked reading projection
- metadata source switch replaces staff and character sections
- reading source switch preserves metadata source
- sync imports a legacy binding without changing selected metadata source
- local item is bound and later detached without deleting the entity

### Migration Tests

Required migrations:

- old binding rows become legacy or confirmed
- old relation rows become legacy
- existing preferences remain readable
- orphan preferences are marked invalid
- backup restore preserves binding state and relation provenance

## Rollout Strategy

Use feature flags or staged internal toggles:

1. read-only binding state migration
2. relation provenance writes for new ingests
3. work detail provenance filtering
4. candidate UI
5. Entity Organize operations
6. legacy repair tools

Avoid a single large migration that changes routing, relation display, and manual management at once.

## Implementation Progress

Completed:

- Passive favourites, history, updates, suggestions, and search list rendering no longer creates entities.
- Binding rows now store `source_kind`, `state`, `created_by`, and `updated_at`; active lookups exclude candidate and rejected bindings.
- Relation rows now store tracking-source provenance and state; details relation sections can be filtered by selected metadata source.
- Details metadata source panel supports long-press removal using the same menu pattern as reading source removal.
- Switching metadata source refreshes the search query from the newly selected tracking title and starts a new search.
- User-facing reading source attach/detach, details candidate binding, source migration, and tracking batch binding now go through `EntityGraphRepository` instead of constructing binding rows directly.
- Backup/WebDAV restore now preserves local protected binding states and skips stale remote binding overwrites.
- Existing Settings -> Entity Organize shows repair diagnostics for orphan preferences, conflicting reading bindings, and stale legacy relations.

Still pending:

- Wire existing Settings -> Entity Organize UI to the manual relation and binding state APIs where gaps remain.
- Add explicit, user-confirmed repair actions for diagnostics surfaced by Entity Organize.
- Add targeted tests for rejected/manual binding overwrite protection and selected metadata source relation filtering.

## Open Decisions

- Whether `confirmed` should be a separate state from `manual`, or whether manual plus confidence is enough.
- Whether relation provenance should point to binding id only, or also store source kind/id as denormalized fields for easier sync repair.
- Whether favourites should default to entity-first open after an entity is manually organized.
- Whether source preferences should be per entity globally or per feature context.
- How aggressively legacy relations should be hidden before relation provenance migration is complete.

## Success Criteria

The governance work is successful when:

- history, updates, subscriptions, and local pages never lose source identity
- metadata source changes cannot leave stale staff, character, or related-work sections visible
- users can manually repair wrong bindings
- sync cannot silently promote stale or orphaned bindings
- all automatic matching is explainable, reversible, and rejectable
- entity graph remains an integration layer, not a replacement for source records
