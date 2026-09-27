# Entity Graph Implementation Plan

> [!WARNING]
> Archived: the entity graph and work ownership model was removed in September 2026, so this page
> does not describe the current app. See [Entity System (Archived)](./index.md).

## Purpose

This document turns the entity-graph discussion into an execution plan for Kototoro.

The goal is not to build a full knowledge graph platform. The goal is to introduce a local, incremental, user-driven entity graph that becomes the primary shell for tracking-driven discovery and detail navigation.

## Scope

This plan covers:

- the entity graph storage and ingestion model
- the detail-page information architecture
- the migration path from tracking-site details to entity details
- the first integration path for source lookup from entities

This plan does not cover:

- a backend service
- global crawling or full-site graph materialization
- a graph database
- AI, embeddings, or recommendation infrastructure
- a full UI redesign of existing source details

## Product-Level Decision

Kototoro should converge to two user-facing detail modes, not three:

- `Entity Details`: the primary detail page for a work or other entity
- `Source Details`: the execution page for a specific source content item

`Tracking Site Details` should remain as a limited raw-reference page for:

- fallback when entity ingestion fails
- direct source-of-truth inspection
- debugging and manual binding flows
- transitional compatibility while the entity flow is still rolling out

This keeps the mental model stable:

- tracking site = truth and metadata authority
- entity graph = local integration layer
- source = playable/readable content

## Core Model Boundaries

### Entity Types

The current entity taxonomy should remain intentionally small:

- `WORK`
- `CHARACTER`
- `PERSON`
- `ORGANIZATION`

This is sufficient for the current feature set and keeps the graph cheap to maintain.

### Staff and Cast Policy

The current and planned mapping should be:

- authors, illustrators, directors, script writers, producers, staff -> `PERSON`
- companies, studios, publishers, circles, production committees -> `ORGANIZATION`

Do not introduce more entity types unless a concrete UI or data-flow requirement appears in at least two real call sites.

### Graph Depth Policy

Graph materialization must stay shallow by default:

- store only user-relevant nodes
- persist 1-hop or 2-hop relationships
- do not auto-expand beyond the currently browsed tracking item
- keep cache deletion cheap and deterministic

## Architecture Rules

The implementation must continue to follow these rules:

- tracking data is the truth for entity metadata
- source data is content access, not entity truth
- UI reads from the local entity graph, not directly from tracking API payloads
- every persisted graph node is cache-like and deletable
- ingestion is triggered by user navigation, not background crawling

## Current Status

As of the current implementation round, Phase 1 through Phase 3 are materially in place:

- Room schema and migration for `entity`, `entity_binding`, and `relation`
- DAO and repository support for graph access and pruning
- tracking-to-entity ingestion pipeline
- basic relation creation for work, character, and person edges
- cross-site binding matcher with auto-bind and weak-bind thresholds
- source adapter contract for `Entity -> SourceResult`

What is not done yet:

- entity details as the main UI entry
- navigation migration from tracking-site details to entity details
- wider source-side entry migration
- richer staff and organization ingestion beyond the currently exposed discovery DTOs

## Detail Page Strategy

### Final Target

The long-term navigation target is:

1. user opens a tracking result
2. app resolves or creates a local entity
3. app opens `Entity Details`
4. user selects a source result
5. app opens the existing `Source Details`

`Tracking Site Details` remains reachable from `Entity Details` as a secondary action such as:

- `View tracking record`
- `Open raw tracking details`

### Why Not Reuse `DetailsActivity` Directly

`DetailsActivity` is still source-content centric:

- it assumes a concrete `Content`
- it bundles reading/playback execution concerns
- it is not a stable home for entity-level metadata aggregation

Forcing entity responsibilities into that page would mix two very different responsibilities and make Phase 4 harder to review safely.

### Why Not Keep Tracking Details as a Third Primary Page

Keeping three parallel primary details would create an unstable mental model:

- users would not know which page is the canonical overview
- internal routing rules would stay inconsistent
- later PERSON or ORGANIZATION details would have no clean home

So the plan is:

- entity details becomes the default detail shell
- source details remains the actionable content page
- tracking raw details becomes a fallback and inspection page

## Data Flow

### Entity-First Tracking Entry

The preferred flow for tracking-site clicks should be:

1. try `findEntityByBinding(service, remoteId)`
2. if found, open `Entity Details`
3. if missing, fetch tracking details
4. map the tracking payload to `TrackingWorkDto`
5. ingest into the entity graph
6. open `Entity Details`
7. if ingestion fails, keep a direct fallback path to raw tracking details

### Initial Mapping Constraints

The current tracking discovery DTO exposes enough for a first work-level page, but not enough for a full Bangumi-style people graph across all services.

So the first integration pass should only map:

- work title
- aliases
- author/staff names when present
- tracking binding identity

Character, cast, and organization ingestion should stay incremental and service-dependent.

## Entity Details MVP

The first entity-details implementation should stay intentionally small.

It should include:

- entity title and type
- aliases
- current bindings
- first-hop related entities
- source adapter lookup results for `WORK`
- explicit action to open raw tracking details when available

It should not include yet:

- a full redesign of the detail visual system
- complex edit flows
- advanced graph browsing
- background graph expansion

## Relation Display Rules

The first page should render only the relations that are meaningful for the current entity type:

- `WORK`: characters, creators, weak related links
- `CHARACTER`: parent work, voice actors
- `PERSON`: created works, voiced characters
- `ORGANIZATION`: associated works or people when later introduced

Direction matters. The UI should not show mirrored edges twice.

## Entity Coverage Plan

### Phase 4A: Entity Detail Shell

Deliverables:

- `EntityDetailsActivity`
- `EntityDetailsFragment`
- `EntityDetailsViewModel`
- router support for opening entity details
- repository read-model support for bindings and first-hop relation rendering

Acceptance criteria:

- an entity can be opened from an entity id
- a tracking item can resolve into an entity page
- the page can still open the raw tracking details when needed

### Phase 4B: Tracking Entry Migration

Deliverables:

- tracking discovery list opens entity-first flow
- category pages open entity-first flow
- other tracking-entry call sites use the same behavior

Acceptance criteria:

- tracking discovery no longer leads users into a long-term third primary details page
- fallback to raw tracking details remains available

### Phase 4C: Source Integration on Entity Page

Deliverables:

- source results rendered inside entity details
- click-through to existing source details

Acceptance criteria:

- a work entity can surface matching source candidates
- the existing `DetailsActivity` stays unchanged as the execution-oriented content page

### Phase 4D: Wider Entry Migration

Deliverables:

- selected local or tracking cross-links migrate toward entity details
- legacy source-first paths remain valid where the app already owns a concrete `Content`

Acceptance criteria:

- entity details becomes the default overview shell
- source details is only used when the user is already selecting concrete content

## Planned Technical Work Breakdown

### Workstream 1: Docs and Navigation Contract

- add this document
- define the two-detail mental model explicitly
- add router APIs for entity-first navigation and raw tracking fallback

### Workstream 2: Entity Detail Read Model

- expose first-hop related entity lookup
- keep view-model orchestration thin
- avoid speculative domain abstractions

### Workstream 3: Tracking Ingestion Bridge

- reuse existing discovery service
- add a small mapper from `TrackingSiteItemDetails` to `TrackingWorkDto`
- keep service-specific enrichment optional

### Workstream 4: Source Result Bridge

- use the existing `EntityGraphSourceAdapter`
- load only for `WORK`
- keep source search bounded by the adapter limits

## Risk Areas

### Risk 1: Discovery DTOs Are Too Thin

Some services only expose author-style metadata in the current abstraction.

Mitigation:

- ship the work-level entity page first
- enrich the DTOs only when there is a concrete UI need
- do not block the page on full cast/staff coverage

### Risk 2: Mixed Navigation During Migration

Multiple call sites may continue to open raw tracking details for some time.

Mitigation:

- centralize the behavior in `AppRouter`
- keep raw tracking details behind an explicit fallback API

### Risk 3: Source Search Noise

Entity-to-source search may return weak matches.

Mitigation:

- keep the current confidence threshold
- limit source count and per-source result count
- treat this page as candidate discovery, not authoritative binding

## Rollback and Safety

This migration is intentionally additive and reversible:

- existing source details remain intact
- raw tracking details remain intact
- entity details can be introduced as a new route without deleting old flows
- router-level fallbacks can restore old behavior if the entity path proves unstable

## Definition of Done for the Current Round

This round is complete when:

- the implementation plan is committed to docs
- an entity details MVP exists
- tracking discovery uses entity-first routing
- raw tracking details remain reachable as a fallback
- Kotlin compilation still passes

## Follow-Up Work After This Round

The next iterations after the MVP should focus on:

- richer PERSON and ORGANIZATION ingestion where tracking data supports it
- improved entity-page presentation and section prioritization
- targeted tests for entity-details view-model orchestration
- selective migration of more cross-links from content pages to entity pages
