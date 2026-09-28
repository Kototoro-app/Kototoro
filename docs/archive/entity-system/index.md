# Entity System (Archived)

> [!WARNING]
> The entity graph and work ownership model described on these pages was **removed in September 2026**
> (database version 84). None of it describes the current app. Kept for maintainers who need the
> history behind old backups, migrations, or design decisions.

Kototoro briefly grouped manga, novels, and video under shared "works" (entities), with favourites,
history, statistics, tracking, and scrobbling owned by the work instead of by each source entry
("projection"). The model was dropped in favour of the projection-first design: every piece of user
state belongs directly to its `manga` row again.

What replaced it, and how existing data was migrated (database `Migration83To84`, old `WORK_*` and
`ENTITY_GRAPH_*` backup sections, WebDAV / Google Drive sync), is recorded in the
[Entity/Work system removal handoff](../../architecture/entity-system-removal-handoff-2026-09.md).

## Archived pages

User guide:

- [Entity System And Organize Guide](./entity-system.md)

Design and implementation plans:

- [Entity Graph Implementation Plan](./entity-graph-implementation-plan.md)
- [Entity Graph Details Unification](./entity-graph-details-unification.md)
- [Entity Graph Hardening Plan](./entity-graph-hardening-plan.md)
- [Entity Graph Governance Remediation Plan (2026-06)](./entity-graph-governance-remediation-plan-2026-06.md)
- [Entity Graph Source Boundary Audit (2026-06)](./entity-graph-source-boundary-audit-2026-06.md)
- [Entity Source Governance Plan](./entity-source-governance-plan.md)
- [Entity Identity Migration Consolidation Plan (2026-06)](./entity-identity-migration-consolidation-plan-2026-06.md)
- [Entity-Centered Work Migration Execution Plan (2026-06)](./entity-centered-work-migration-execution-plan-2026-06.md)
- [Entity Space Implementation Plan (2026-07)](./entity-space-implementation-plan-2026-07.md)
- [Entity Content-Type Merge Bug Analysis (2026-07)](./entity-content-type-merge-bug-analysis-2026-07.md)
- [Metadata Write Audit Plan (2026-06)](./metadata-write-audit-plan-2026-06.md)
- [External Backup Fast Import and Deferred Entity Consolidation (2026-08)](./external-backup-fast-import-consolidation-plan-2026-08.md)

Work migration:

- [Work Migration Status Audit (2026-06)](./work-migration-status-audit-2026-06.md)
- [Work Migration Sync Isolation Plan (2026-06)](./work-migration-sync-isolation-plan-2026-06.md)
- [Work Ownership Matrix (2026-06)](./work-ownership-matrix-2026-06.md)
- [Work Sync Schema and Restore Isolation Spec (2026-06)](./work-sync-schema-and-restore-isolation-spec-2026-06.md)
