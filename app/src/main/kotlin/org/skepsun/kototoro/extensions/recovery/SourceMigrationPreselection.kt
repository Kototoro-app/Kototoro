package org.skepsun.kototoro.extensions.recovery

/**
 * T5.5 — Pure preselection of the works affected by a missing source.
 *
 * The strict [SourceMigrationPreselection.preselectAffectedWorks] maps a sourceKey to the
 * local content (manga) ids that came from that source. Those ids are exactly what the
 * existing SourceMigration workbench consumes:
 *
 *  - `SourceMigrationPanel(initialSelectedContentIds = ids.toSet(), ...)`
 *    (favourites/ui/migration/compose/SourceMigrationPanel.kt) seeds the workbench
 *    selection; the panel then forwards them to `SourceMigrationViewModel.setSelectedContentIds`.
 *  - The `worksBySource` map is built from the favourites catalog, e.g.
 *    `EntityOrganizeRepository.listFavouriteContents(sourceName = ...).associate { it.manga.id }`
 *    grouped by `it.manga.source` (favourites/domain/EntityOrganizeRepository.kt).
 *
 * Keeping the function pure (no Android / DAO / repository deps) makes the preselection rule
 * trivially unit-testable and lets the caller decide where the sourceKey -> source-name
 * resolution happens (that resolution is domain wiring owned by the main session).
 */
object SourceMigrationPreselection {

    /**
     * Returns the distinct manga ids for [sourceKey] from [worksBySource], or an empty list
     * when the key is unknown / has no works.
     *
     * @param sourceKey strict source key, e.g. `MIHON_123` / `TSUNDOKU_9001`.
     * @param worksBySource map of source key -> local manga ids observed from that source.
     */
    fun preselectAffectedWorks(
        sourceKey: String,
        worksBySource: Map<String, List<Long>>,
    ): List<Long> {
        return worksBySource[sourceKey].orEmpty().distinct()
    }

    /**
     * Convenience overload exposing the same rule as an immutable `Set` — the shape the
     * migration workbench seeds via `initialSelectedContentIds`.
     */
    fun preselectAffectedWorksAsSet(
        sourceKey: String,
        worksBySource: Map<String, List<Long>>,
    ): Set<Long> {
        return preselectAffectedWorks(sourceKey, worksBySource).toSet()
    }
}
