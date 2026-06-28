# Entity System And Organize Guide

Kototoro uses the entity system as a stable work identity layer. Its job is to keep one real work represented as one library item, even when that work has several source entries, local files, or tracking-site matches.

In daily use, this means favorites, categories, history, progress, statistics, and tracking state belong to the work, not to one replaceable source entry. Source entries are still required for reading, playback, updates, and downloads, but they are treated as windows into the work rather than the work itself.

This guide follows the current identity rules:

- One work should have one stable local identity.
- Source entries are projections attached to that identity.
- Candidates stay suggestions until you explicitly accept them.
- Restore and sync data must be mapped to local works; remote IDs are not copied directly as local IDs.
- Characters, people, organizations, and relations may help details-page navigation, but they do not own favorites, history, progress, or sync state.

## Key Terms

| Term | Meaning |
| :--- | :--- |
| Work | The stable identity for one title in your library. This is what favorites, history, and progress are organized around. |
| Projection | A readable or playable entry from a source, local file, or extension. A work can have multiple projections. |
| Default projection | The source entry Kototoro currently prefers when opening details, reading, playback, or updates for the work. Changing it does not create a new work. |
| Tracking binding | A link from a work to a tracking service such as AniList, MAL, Kitsu, Bangumi, Shikimori, MangaUpdates, or a local mapping dataset. |
| Metadata authority | The source Kototoro trusts for work-level details such as title, cover, rating, or tracking metadata. It is separate from the source used to read or play content. |
| Candidate | A suggested match that Kototoro found but has not applied until you review or accept it. |

## Identity Rules In Plain Language

The internal IDs are not normally visible, but their roles explain why Entity Organize behaves carefully.

| Internal concept | User-facing meaning | Important rule |
| :--- | :--- | :--- |
| `entity_id` | This device's local work identity. | It owns favorites, history, statistics, tracking state, and categories on this device. |
| `sync_id` | The cross-device identity used by backup, sync, and restore. | It helps map a restored work to the correct local work, but it is not a source entry and is not used to load chapters or media. |
| `manga_id` | A local source entry or file entry. | It can open details, chapters, reading, playback, download, or update tasks, but it is not the owner of the work. |
| `entity_binding` | Evidence that a source entry belongs to a work. | Strong bindings can confirm ownership; weak title matches only create candidates. |
| `preferred_local_manga_id` | The current default projection. | Switching the default source changes where you read from, not which work the user state belongs to. |

The practical result is simple: Kototoro can switch or replace sources without losing the work-level state, but it will not silently merge two works only because their titles look similar.

## When To Use Entity Organize

Use Entity Organize when your library has an identity boundary problem:

- The same title appears multiple times in favorites because it came from different sources.
- A favorite opens the wrong source, missing chapters, or an outdated projection.
- Tracking metadata is missing, duplicated, or attached to the wrong work.
- A work was accidentally merged with another title and should be split apart.
- You imported, restored, or synced a large library and want to review work identities before continuing.

You do not need to run it after every normal read, favorite, or source switch. It is a maintenance and review tool, not a daily requirement.

## Open Entity Organize

There are two practical entry points:

1. Open **Settings** and choose **Entity organize** to review the full favorites archive.
2. Open **Favorites**, long-press items, select the titles you want to review, then choose **Entity organize** from the selection action. This limits the run to the selected favorites.

The first screen tells you whether the run starts from **All favorites** or a **Manual selection**. For large libraries, start with a manual selection when you already know which titles need attention.

## Understand The Workbench

The main table is the Entity workbench. Each row represents a work or a candidate group. It exists to help you confirm identity boundaries, not to auto-fix every piece of old metadata.

- **Entity**: the stable work identity and current state.
- **Local projections**: source entries or local files attached to the work.
- **Tracking candidates**: suggested tracking-site matches.
- **Reading candidates**: usable source hits that can be attached or activated.

Use the status filter and sort controls to focus the table:

- **Needs action** shows rows that still need review.
- **Checked** shows rows you already selected for the current operation.
- **Action first** prioritizes rows where Kototoro found something actionable.
- **Best match** is useful after previewing tracking or reading candidates.
- **Most projections** helps find works that already have several attached entries.

The workbench is the safest place to accept common suggestions. Detailed panels below the table are mainly for configuring searches and executing each stage.

## The Three Identity Actions

The identity model has three core actions:

| Action | What it changes | What it must not do |
| :--- | :--- | :--- |
| Merge works | Confirms that multiple projections or work rows are the same work. | It must not rely on fuzzy title similarity alone. |
| Split projections | Moves one source entry out of the wrong work into a separate or corrected work. | It must not delete the favorite, history, or source entry. |
| Choose default source | Sets the preferred projection for details, reading, playback, or updates. | It must not change the work identity or rewrite cross-device identity. |

Tracking binding, metadata source selection, diagnostics, and relation cleanup support these actions, but they are evidence or metadata maintenance. They are not separate owners of your library state.

## Stage 1: Entity Merge

Entity merge groups local projections that appear to represent the same title.

Recommended workflow:

1. Open the **Entity** stage.
2. Select **Find merge candidates**.
3. Review the proposed groups. Strict candidates require matching content type plus matching titles, known aliases, or other strong binding evidence.
4. Leave fuzzy title matching off unless you need broader suggestions. Fuzzy candidates are not selected by default and should be checked manually.
5. Select only groups that clearly represent the same work.
6. Run **Merge selected entities**.

For manual merge, check at least two works with the same content type in the merge table, then use **Manual merge checked works**. Do not merge different titles just because their names, covers, or aliases are similar.

Merging keeps the surviving work identity. The default source may change later, but changing the default source is not a reason to create a new work identity.

## Stage 2: Tracking Bind

Tracking bind links works to external metadata or tracking identities. Treat it as binding evidence and metadata authority, not as the owner of favorites or history.

Recommended workflow:

1. Open the **Tracking** stage.
2. Choose tracking sites in priority order.
3. Choose a candidate source strategy:
   - **Local first** prefers installed local mappings and aliases before online results.
   - **API first** prefers aggregate API aliases and site mappings first.
   - **Local only** avoids aggregate API results.
   - **API only** avoids local mapping data.
4. Use **Preview matches**.
5. Review low-confidence rows manually. Content type still has to match, but title similarity can still be wrong.
6. Accept the matches you trust in the workbench.
7. Run **Bind tracking entities**.

If a row is marked low confidence, treat it as a suggestion. Skip it unless the title, type, and service entry clearly match.

Tracking cache, tracking-site links, and title similarity are not enough to confirm a work identity by themselves. They can explain why a candidate was suggested, but you still need to accept the binding before it becomes trusted.

## Stage 3: Usable Projection Completion

Projection completion searches selected reading or playback sources and attaches a usable source entry to each work. This is mainly how you choose or improve the default source for a work.

Use it when a work has tracking metadata or a favorite identity but lacks a good source entry for reading or playback.

Recommended workflow:

1. Open the **Projection** stage.
2. Select target sources and put reliable sources first.
3. Use **Preview usable projections**.
4. Review each hit:
   - **Activate existing** switches to an already attached projection.
   - **Attach new** adds the found source entry and activates it.
5. Accept only hits that clearly point to the same work.
6. Run **Execute projection completion**.

Projection completion processes the accepted rows only. If no hit is accepted, execution will not attach anything for that work.

When the result is **Activate existing**, Kototoro switches to a projection that is already attached. When the result is **Attach new**, Kototoro creates a binding between the found source entry and the current work. In both cases, the work-level favorite and history stay on the work.

## Diagnostics And Repair

Entity Organize can also show diagnostics for old or inconsistent data. These diagnostics should point to real boundary risks: wrong work membership, wrong binding evidence, metadata authority drift, cache leftovers, or legacy import residue.

| Diagnostic | What it usually means | Typical action |
| :--- | :--- | :--- |
| Suspect mismerge | A projection title no longer matches the work name or aliases. | Split the projection into a standalone work if it is a different title. |
| Suspect tracking | A tracking binding title does not match the local work. | Reject the tracking binding, then bind the correct entry later. |
| Active source | The active metadata source points to an incompatible tracking entry. | Repair active source. |
| Metadata mirror, override mirror, reading status mirror | Old projection-level shadow data duplicates work-level state. | Prune mirrors when the effective result is unchanged. |
| Legacy relation | Old relation data lacks current provenance. | Hide stale legacy relations if they are no longer useful. |

Split and detach actions keep the favorite and history item. They change the work binding, not the underlying content entry.

Repair actions should not turn weak candidates into confirmed identities automatically. If the correct answer depends on whether two titles are really the same work, review and confirm it manually.

## Split Out And Detach

**Split out** and **Detach** both remove a projection from the current work binding, but they have different intent.

| Action | What it does | Use it when | Result |
| :--- | :--- | :--- | :--- |
| Split out | Moves the selected projection into its own standalone work identity. | The projection is a real title, but it was merged into the wrong work. | The title remains visible as a separate work. Favorite and history are kept on the corrected work identity. |
| Detach | Removes only the selected projection's binding to the current work. | The binding is wrong, stale, or not trustworthy, and you do not want to confirm a replacement yet. | The source entry is unbound and can be rebound later. It is no longer active evidence for the current work. |

In plain language:

- Use **Split out** when you know where the projection belongs: it should become a separate work.
- Use **Detach** when you only know the current binding is wrong: remove the evidence first, then decide the correct work later.

Neither action deletes the downloaded files or source entry. **Split out** moves matching work-level state for that projection to the new work identity. **Detach** does not create a new work and does not move state to a new owner. It removes the binding, clears or replaces the current work's default source, and removes the detached projection from normal favorite/history entry points when no fallback projection exists.

### Split out

Split out is the normal fix for an accidental merge.

Example: `Title A` and `Title B` were merged into one work because a source alias or imported legacy relation was misleading. Open the merged work, find the projection for `Title B`, then use **Split out**. Kototoro moves that projection into a standalone work so future favorites, history, tracking, and source selection can be handled separately.

After splitting out:

1. Check both works in Favorites or Details.
2. Choose the default source for each work if needed.
3. Rebind tracking metadata if one of the works still points to the wrong service entry.

### Detach

Detach is more conservative than split out. It says: this projection should not be trusted as evidence for the current work, but Kototoro should not automatically decide its new identity yet.

Use Detach for cases like:

- A source changed URLs and the old binding now points to the wrong title.
- A legacy import created a suspicious binding, but you are not sure what the correct work should be.
- A candidate was accepted by mistake and you want to remove that evidence before rebinding.
- The same source entry should be searched or attached again through a safer workflow.

After detaching:

1. If the old work still has another projection, Kototoro switches normal entry points to that fallback projection.
2. If there is no fallback projection, the old favorite/history entry stops using the detached projection as its visible anchor.
3. Use **Projection** completion to find and attach the correct source entry, or open the source entry manually and bind it again through the normal work flow.
4. Use **Tracking** bind only if the work also needs metadata or tracking repair.
5. If the detached projection is actually a separate title, use split or merge actions later to place it under the correct work.

Detach does not mark the old evidence as permanently rejected. A later explicit attach or accepted candidate can bind the same source entry again. Until then, the projection is an unowned source entry that may appear as a candidate in organize flows, not as an independent favorite work. Detach should not be used as a cleanup shortcut for every confusing row; if the projection clearly belongs to another title, **Split out** gives the library a better final shape.

## Rebuild Entity Identities

**Rebuild identities** is an advanced maintenance action. It creates a local backup first, rebuilds work identities from strong source URL evidence, and blocks WebDAV auto upload until you confirm the result.

Use it only when ordinary merge, split, tracking repair, and projection completion cannot recover the library shape. After rebuilding:

1. Review Favorites and affected details pages.
2. Re-run tracking bind or projection completion if needed, because tracking bindings and preferred projections may need explicit review again.
3. Use **Confirm result and allow sync** only after the rebuilt library looks correct.

Do not confirm sync immediately if the result looks wrong. Restore from the generated local backup or fix the affected identities first.

## Common Workflows

### Merge duplicate favorites

1. In Favorites, select the duplicate rows.
2. Open **Entity organize**.
3. In the **Entity** stage, find or manually check the duplicate works.
4. Run merge.
5. Return to Favorites and verify that the title appears as one work.

### Fix wrong tracking metadata

1. Open **Entity organize** for the affected title.
2. Check diagnostics for **Suspect tracking**.
3. Reject the wrong binding if needed.
4. Open the **Tracking** stage, preview matches, accept the correct service entry, and run bind.

### Add a better reading source

1. Open **Entity organize** for the affected work.
2. Open the **Projection** stage.
3. Put the preferred source first in target source priority.
4. Preview usable projections.
5. Accept the correct hit and execute projection completion.

### Undo an accidental merge

1. Open **Entity organize**.
2. Find the affected work and expand local projections.
3. Use **Split out** for each projection that belongs to another real title.
4. Use **Detach** only when the projection is suspicious but you are not ready to assign it to a standalone work.
5. Rebind tracking and source projections for all affected works if needed.

### Clean up duplicate imported projections

If the same Mihon or external backup was imported twice, one work may contain multiple equivalent projections from the same source entry. Normal library pages de-duplicate equivalent projections for display, but Entity Organize still shows the underlying projections so you can clean them up.

1. Open **Entity organize**.
2. Find the affected work and expand **Local projections**.
3. Keep the projection you want to use as the default source.
4. Use **Detach** on duplicate projections that are only extra imported copies of the same source entry.
5. Do not use **Split out** unless the projection is actually a different title.

After detaching duplicates, they become unowned source projections. They do not become separate favorite works, and they can be rebound later if needed.

### Choose a different default source

1. Open **Entity organize** for the work.
2. Open the **Projection** stage.
3. Preview usable projections or locate an already attached projection.
4. Accept **Activate existing** or **Attach new** for the source you want.
5. Execute projection completion and verify the details page opens the preferred source.

This changes only the preferred projection. It does not move favorites, categories, history, or statistics to a source-specific owner.

## Safety Notes

- Always review candidate rows before execution. Entity Organize is explicit by design; suggestions are not a guarantee.
- Keep fuzzy matching disabled unless strict matching is not enough.
- Do not use title similarity, cover similarity, or tracking cache alone as proof that two works are the same.
- For large archives, work in smaller manual selections first.
- Run WebDAV sync only after you are satisfied with the organized result. Restore and sync map remote identities to local works instead of directly copying remote local IDs.
- If you use the rebuild action, keep the generated backup until you have verified the library on the current device.
