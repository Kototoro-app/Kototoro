# Custom Spaces Architecture

## Goals

Custom Spaces let users define additional media contexts from content types, source languages, and source
implementations. The feature must preserve the current built-in Manga, Novel, and Anime spaces and keep the
Spaces-disabled path operationally equivalent to the application without Spaces.

The primary performance requirement is:

- When Spaces are disabled, no custom-space definitions are queried, no rules are compiled, no per-space navigation
  state is restored, and existing unscoped repository queries remain unchanged.
- The only permanent cost while disabled is observing the existing `entity_space_enabled` preference.
- Disabled mode must not add a Space predicate to existing SQL. It selects the original repository method instead.

## User-visible behavior

- Built-in Spaces remain available and cannot be deleted.
- Custom Spaces can be named, reordered, enabled, disabled, and deleted.
- Custom Spaces use the first visible grapheme of their name as the switcher monogram; built-ins retain semantic icons.
- A rule uses AND between dimensions and OR within a dimension. An empty dimension is unrestricted.
- A work may appear in multiple Spaces. The current matching Space wins; otherwise explicit Space priority wins;
  built-in Spaces are the fallback.
- Direct links and details remain accessible even when the current Space rule does not match them.
- Disabling Spaces restores global browse, favorites, history, navigation, and the Continue Reading FAB.

## Rule model

```kotlin
data class SpaceRule(
    val contentTypes: Set<ContentType>,
    val sourceLanguages: Set<String>,
    val sourceKinds: Set<SourceKind>,
)
```

Language means source language, matching the existing language preset feature. Sources with no locale are treated as
unknown or multilingual and can be selected explicitly. Locale values are normalized to lower-case ISO language codes.

### Language preset integration

The current language preset implementation stores both selected language codes and a snapshot of matching source
names. Consumers filter by the stored source names. Installing or removing a source therefore does not update a preset
until the user saves it again.

Language presets and custom Spaces must share one source rule resolver:

1. Selected languages are authoritative.
2. Matching source names are derived from the current source registry.
3. Source registry changes invalidate the compiled result automatically.
4. The stored source-name set is retained for schema compatibility only; runtime consumers do not treat it as authoritative.

The selected language preset is scoped by Space. Built-in and custom Spaces store only the selected preset ID under a
dedicated route-preference key. Disabling Spaces restores the global preset selection. This scoping is independent of
the page-preferences toggle so disabling per-Space layout memory does not disable language-preset scoping.

## Data model

Custom definitions are stored in Room. Preference JSON is not used for structured Space data.

`space_definition`:

- `space_id TEXT PRIMARY KEY`, using `custom:<uuid>` for user definitions
- `title TEXT NOT NULL`
- `sort_key INTEGER NOT NULL`
- `enabled INTEGER NOT NULL`
- `content_types TEXT NOT NULL`
- `source_languages TEXT NOT NULL`
- `source_kinds TEXT NOT NULL`
- `created_at INTEGER NOT NULL`
- `updated_at INTEGER NOT NULL`
- `deleted_at INTEGER NOT NULL`

Built-in definitions remain code-owned and are merged with enabled custom definitions by `SpaceCatalogRepository`.
Sessions and route preferences keep using their existing `space_id` foreign keys and are deleted when a custom Space
is permanently removed.

Built-in Spaces use the same immutable `SpaceContext` and policy pipeline as custom Spaces, but they are not rows in
`space_definition`. Their content-type sets are precomputed constants and their language/source-kind dimensions are
unrestricted, so enabling Spaces adds no rule compilation for the three built-ins.

## Runtime structure

`SpaceCatalogRepository` is the single source of truth for available definitions. It exposes a disabled fast path:

```kotlin
spacesEnabled.flatMapLatest { enabled ->
    if (enabled) observeBuiltInsAndCustomDefinitions()
    else flowOf(BuiltInSpaces.contexts)
}
```

Constructors perform no database work and start no source-registry jobs. Definition observation and rule compilation
start only after Spaces become enabled.

`CompiledSpaceRule` contains content type names and matching source names. Source language and kind matching occurs
only when the definition or source registry changes, never once per displayed work.

## Query strategy

- Disabled: call existing global DAO/repository methods exactly as before.
- Enabled: resolve the active definition once and query by compiled content types and source names.
- Do not create a content-to-space membership table. It would add write amplification proportional to the number of
  Spaces even while users are not viewing them.
- Keep or add an index over `manga(content_type, source)` if query plans show it is required.
- Browse source filtering uses the already emitted enabled source list and the compiled rule.

## Navigation and resume state

- Create navigation state only for the active Space.
- Persist the outgoing state before switching.
- Keep at most the most recently used states in an in-memory LRU cache; the initial limit is three.
- Load route preferences only for the active Space.
- Do not continuously observe recent content for every Space. Load resume summaries when opening the switcher.
- Existing session and route-preference repositories validate against the current catalog rather than hard-coded IDs.
- Content-list routes are structurally restored without consulting the runtime source registry. Extension discovery is
  transiently empty during cold start and must not destructively truncate a persisted navigation snapshot.
- Active readers and players are isolated in hidden document tasks while immersive Space switching is enabled. The
  in-memory registry restores the exact task ID for each Space instead of reordering by Activity class, which is
  ambiguous when multiple Spaces use the same reader implementation.

## Source registry invalidation

The resolver observes the canonical enabled-source stream. Registry emissions are distinct by a compact source
signature containing source name, normalized language, content type, and source kind. A new signature recompiles active
language presets and custom Space rules. Recompilation is performed on `Dispatchers.Default` and publishes one immutable
snapshot.

## Delivery sequence

1. Introduce the catalog, Room definition storage, rule model, and unit tests.
2. Add the shared reactive source rule resolver and migrate language preset consumers away from static source snapshots.
3. Replace hard-coded built-in validation in Space repositories with catalog validation.
4. Make content policy, browse scope, switcher presentation, navigation sessions, and resume state catalog-driven.
5. Add custom Space management UI under Settings > Users > Spaces.
6. Add disabled-path and scaling benchmarks before removing the implementation flag.

## Performance acceptance

- Spaces disabled: no `space_definition` Room query and no source-rule compilation.
- Spaces disabled: no additional per-Space coroutine, navigation controller, or history/favorites observer.
- Cold and warm startup `timeToInitialDisplay` P50/P95 regression at most 2 percent.
- Startup allocations regression at most 1 percent.
- Existing global SQL is unchanged in disabled mode.
- No statistically significant regression in main-list slow-frame rate.
- Test configurations with 0, 16, and 64 custom definitions.

The feature should initially cap enabled custom Spaces at 16. This is an interaction and state-management bound, not a
rule-matching limitation.
